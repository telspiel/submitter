package com.noesis.submitter;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.commons.codec.DecoderException;
import org.apache.commons.codec.binary.Hex;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.web.util.UriUtils;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.noesis.domain.constants.Constants;
import com.noesis.domain.constants.ErrorCodesEnum;
import com.noesis.domain.persistence.NgKannelInfo;
import com.noesis.domain.persistence.NgMisMessage;
import com.noesis.domain.persistence.NgUser;
import com.noesis.domain.platform.MessageObject;
import com.noesis.domain.service.KannelInfoService;
import com.noesis.domain.service.NgNotesService;
import com.noesis.domain.service.StaticDataService;
import com.noesis.domain.service.UserCreditMapService;
import com.noesis.domain.service.UserService;
import com.noesis.producer.MessageSender;

public class MessageSubmitter {

	private static final Logger logger = LogManager.getLogger(MessageSubmitter.class);

	private int maxPollRecordSize;

	private CountDownLatch latch = new CountDownLatch(this.maxPollRecordSize);

	@Autowired
	private ObjectMapper objectMapper;

	@Autowired
	private StaticDataService staticDataService;

	@Autowired
	private NgNotesService ngNotesService;

	@Value("${app.name}")
	private String appName;

	@Value("${kafka.topic.name.mis.object}")
	private String kafKaDbObjectQueue;

	@Value("${kafka.consumer.sleep.interval.ms}")
	private String consumerSleepInterval;

	@Value("${split.unicode.message.bytes}")
	private String unicodeSplitBytes;

	@Value("${split.plain.message.bytes}")
	private String plainMessageSplitBytes;

	@Autowired
	private UserService userService;

	@Autowired
	private UserCreditMapService userCreditMapService;

	@Autowired
	private KannelInfoService kannelInfoService;

	@Autowired
	MessageSender messageSender;

	public MessageSubmitter(int maxPollRecordSize) {
		this.maxPollRecordSize = maxPollRecordSize;
	}

	public CountDownLatch getLatch() {
		return this.latch;
	}

	@KafkaListener(id = "${app.name}", topics = {
			"${kafka.submitter.topic.name}" }, groupId = "${kafka.submitter.topic.name}", idIsGroup = false)
	public void receive(List<String> messageList, @Header(KafkaHeaders.RECEIVED_PARTITION_ID) List<Integer> partitions,
			@Header(KafkaHeaders.OFFSET) List<Long> offsets) throws IOException {
		logger.info("start of batch receive of size: " + messageList.size());
		for (int i = 0; i < messageList.size(); i++) {
			int messageSendingRetryCount = 1;
			boolean continueMessageProcessing = true;
			boolean isMisRequired = true;
			logger.info("received message='{}' with partition-offset='{}'", messageList.get(i),
					(new StringBuilder()).append(partitions.get(i)).append("-").append(offsets.get(i)).toString());
			MessageObject messageObject = convertReceivedJsonMessageIntoMessageObject(messageList.get(i));
			try {
//				MessageObject messageObject = convertReceivedJsonMessageIntoMessageObject(messageList.get(i));
				if (messageObject != null) {
					String userName = messageObject.getUsername();
					NgUser user = userService.getUserByName(messageObject.getUsername());

					// Round Robin Routing
					if (continueMessageProcessing)
						isMisRequired = selectKannelAndSendMessage(messageObject, messageSendingRetryCount,
								continueMessageProcessing, userName, user, isMisRequired);

					// Dump Message into DB for MIS.
					logger.info("Is MIS Required after sending long message: {}", Boolean.valueOf(isMisRequired));
					saveMessageInMis(isMisRequired, messageObject);
				}
			} catch (Exception e) {
				logger.error("Exception occured while processing message. Hence skipping this message: {} "
						+ (String) messageList.get(i));
				e.printStackTrace();
				messageObject.setErrorCode("077");
				messageObject.setErrorDesc(ErrorCodesEnum.NO_ACTIVE_KANNEL_FOUND.getErrorDesc());
				messageObject.setStatus(Constants.MESSAGE_STATUS_REJECTED);
				String ngMisMessageObject;
				try {
					ngMisMessageObject = convertMessageObjectToMisObjectAsString(messageObject);
					messageSender.send(kafKaDbObjectQueue, ngMisMessageObject);
					sendRejectedDlr(messageObject);
				} catch (UnsupportedEncodingException | DecoderException e1) {
					logger.error("{} Exception occured while processing message. Hence skipping this message: {} ",
							messageObject.getMessageId() + messageList.get(i));
					e1.printStackTrace();
				}
			}
		}
	}

	private void saveMessageInMis(boolean isMisRequired, MessageObject messageObject) {
		if (isMisRequired)

			// saveMessageInMis(messageObject);
			try {
				String ngMisMessageObject = convertMessageObjectToMisObjectAsString(messageObject);
				if (ngMisMessageObject != null) {
					this.messageSender.send(kafKaDbObjectQueue, ngMisMessageObject);
				} else {
					logger.error("Error while converting message object into DB Object with message Id {} ",
							messageObject.getMessageId());
				}
			} catch (UnsupportedEncodingException | DecoderException e) {
				logger.error("Error while converting message object into DB Object with message Id {} ",
						messageObject.getMessageId());
				e.printStackTrace();
			}
	}

	private MessageObject convertReceivedJsonMessageIntoMessageObject(String messageObjectJsonString) {
		MessageObject messageObject = null;
		try {
			messageObject = objectMapper.readValue(messageObjectJsonString, MessageObject.class);
		} catch (Exception e) {
			logger.error("Dont retry this message as error while parsing json string: " + messageObjectJsonString);
			e.printStackTrace();
		}
		return messageObject;
	}

	private boolean selectKannelAndSendMessage(MessageObject messageObject, int messageSendingRetryCount,
			boolean continueMessageProcessing, String userName, NgUser user, boolean isMisRequired) throws Exception {
		String[] kannelIdsArray;

		if (messageSendingRetryCount > 1) {
			logger.error("Message {} got failed as Kannel {} was not reachable. Going to try from failover group ",
					messageObject.getMessageId(), messageObject.getKannelId());

			ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
					"Message will try from fail over group as kannel was not reachable. Original group was "
							+ messageObject.getGroupId() + " and kannel was " + messageObject.getKannelId());
			messageObject.setGroupId(staticDataService.getFailOverRoutingGroup(messageObject.getGroupId()));
		}

		String kannelIds = staticDataService.getKannelIdsForGroupId(Integer.toString(messageObject.getGroupId()));

		logger.info("Kannel ids for group id {} are {}", messageObject.getGroupId(), kannelIds);
		if (kannelIds.contains("#")) {
			kannelIdsArray = kannelIds.split("#");
		} else {
			kannelIdsArray = new String[] { kannelIds };
		}

		logger.info("Kannel ids array from cache size is: " + kannelIdsArray.length);
		int selectedKannelIndex = 0;
		if (kannelIdsArray != null) {
			List<NgKannelInfo> activeKannelInfoList = kannelInfoService.removeInActiveKannelsFromArray(kannelIdsArray);

			if (activeKannelInfoList == null || (activeKannelInfoList != null && activeKannelInfoList.size() == 0)) {
				ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
						"Message will try from fail over group as no active kannel was found in group. Original group was "
								+ messageObject.getGroupId());

				messageObject.setGroupId(staticDataService.getFailOverRoutingGroup(messageObject.getGroupId()));
				kannelIds = staticDataService.getKannelIdsForGroupId(Integer.toString(messageObject.getGroupId()));
				logger.info("Kannel ids for group id {} are {}", messageObject.getGroupId(), kannelIds);
				if (kannelIds.contains("#")) {
					kannelIdsArray = kannelIds.split("#");
				} else {
					kannelIdsArray = new String[] { kannelIds };
				}
				if (kannelIdsArray != null) {
					activeKannelInfoList = kannelInfoService.removeInActiveKannelsFromArray(kannelIdsArray);
					if (activeKannelInfoList == null
							|| (activeKannelInfoList != null && activeKannelInfoList.size() == 0)) {
						ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
								"Message will be skipped as no active kannel was found in fail over group. Faileover group was "
										+ messageObject.getGroupId());

						messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED); // Replace from "FAILED" to
																					// dynamically get FAILED
																					// status(Aman)
						messageObject.setErrorCode(ErrorCodesEnum.NO_ACTIVE_KANNEL_FOUND.getErrorCode());
						messageObject.setErrorDesc(ErrorCodesEnum.NO_ACTIVE_KANNEL_FOUND.getErrorDesc());
						continueMessageProcessing = false;
						return true;
					}
				}
			}
			if (activeKannelInfoList != null && activeKannelInfoList.size() > 1) {

				for (int i = 0; i < activeKannelInfoList.size(); i++) {
					selectedKannelIndex = getRandomNumberInRange(0, activeKannelInfoList.size() - 1);
					NgKannelInfo ngKannelInfo = activeKannelInfoList.get(selectedKannelIndex);
					logger.info("Getting the information about kannel :" + ngKannelInfo);
				}
			}
			NgKannelInfo selectedKannel = activeKannelInfoList.get(selectedKannelIndex);
			logger.info("Selected Kannel Id is :" + selectedKannel.getId());
			messageObject.setKannelId(selectedKannel.getId());

			if (continueMessageProcessing && selectedKannel.getKannelUrl().contains("templateid")
					&& (messageObject.getTemplateId() == null || messageObject.getTemplateId().length() == 0))
				continueMessageProcessing = validateMessageTemplate(messageObject, selectedKannel,
					continueMessageProcessing, isMisRequired, user);

			messageObject.setOrgMessage(messageObject.getMessage());
			Integer messageSplitCount = messageObject.getSplitCount();
			if ((continueMessageProcessing && messageSplitCount != null && messageSplitCount > 1)
					|| (messageObject.getIsMultiPart() != null && messageObject.getIsMultiPart().equals("Y"))) {
				messageObject.setIsMultiPart("Y");

				if (continueMessageProcessing && messageObject.getMessageType() != null
						&& (messageObject.getMessageType().equals("UC")
								|| messageObject.getMessageType().equals("FU"))) {
					logger.info("Long {} message found. Going to split and send distinct messages.",
							messageObject.getMessageType());

					messageObject.setFullMsg(messageObject.getMessage());
					Integer ucSplitBytes = Integer.parseInt(unicodeSplitBytes);
					String concatinatedMessageIds = messageObject.getRequestId();
					String[] messageIdsArray = concatinatedMessageIds.split("#");
					SplittedMessage sm = MessageSubmitterUtil.splitMessage(hexToByteArray(messageObject.getMessage()),
							ucSplitBytes, "UC");
					byte[][] byteMessagesArray = sm.getByteMessagesArray();
					byte[][] byteUdhArray = sm.getByteUdhArray();

					logger.info("***** Important Check. Dont Ignore ******");
					logger.info("SMPP Split count {} and Submitter Split Count {} and Total messages ids count {} ",
							messageObject.getSplitCount(), byteMessagesArray.length, messageIdsArray.length);

					for (int i = 0; i < byteMessagesArray.length; i++) {
						String hexStringToSend = convertByteArrayToHexString(byteMessagesArray[i]);
						String hexUdhToSend = convertByteArrayToHexString(byteUdhArray[i]);
						logger.info("Splitted part [{}] udh hex string is : {}", i, hexUdhToSend);
						logger.info("Splitted part [{}] message hex string is : {}", i, hexStringToSend);

						messageObject.setMessageId(messageIdsArray[i]);
						// messageObject.setRequestId(messageIdsArray[i]);
						messageObject.setUdh(hexUdhToSend);
						messageObject.setMessage(hexStringToSend);
						if (continueMessageProcessing)
							deductCreditAndSendMessage(messageObject, 1, continueMessageProcessing, userName, user,
									selectedKannel);
						saveMessageInMis(true, messageObject);
					}
					isMisRequired = false;
					return false;
				}

				if (continueMessageProcessing && messageObject.getMessageType() != null
						&& (messageObject.getMessageType().equals("PM")
								|| messageObject.getMessageType().equals("FL"))) {
					logger.info("Long {} message found. Going to split and send distinct messages.",
							messageObject.getMessageType());
					messageObject.setFullMsg(messageObject.getMessage());
					Integer pmSplitBytes = Integer.parseInt(plainMessageSplitBytes);
					String concatinatedMessageIds = messageObject.getRequestId();
					String[] messageIdsArray = concatinatedMessageIds.split("#");
					SplittedMessage sm = MessageSubmitterUtil.splitPlainMessage(messageObject.getMessage().getBytes(),
							pmSplitBytes, "PM");
					byte[][] byteMessagesArray = sm.getByteMessagesArray();
					byte[][] byteUdhArray = sm.getByteUdhArray();
					logger.info("***** Important Check. Dont Ignore ******");
					logger.info("SMPP Split count {} and Submitter Split Count {} and Total messages ids count {} ",
							messageObject.getSplitCount(), byteMessagesArray.length, messageIdsArray.length);

					messageObject.setSplitCount(byteMessagesArray.length);
					for (int i = 0; i < byteMessagesArray.length; i++) {
						String hexStringToSend = new String(byteMessagesArray[i]);
						String hexUdhToSend = pmToHexString(new String(byteUdhArray[i]));
						logger.info("Splitted part [{}] udh hex string is : {}", i, hexUdhToSend);
						logger.info("Splitted part [{}] message hex string is : {}", i, hexStringToSend);
						messageObject.setMessageId(messageIdsArray[i]);
						// messageObject.setRequestId(messageIdsArray[i]);
						messageObject.setUdh(hexUdhToSend);
						messageObject.setMessage(hexStringToSend);
						if (continueMessageProcessing)
							deductCreditAndSendMessage(messageObject, 1, continueMessageProcessing, userName, user,
									selectedKannel);
						saveMessageInMis(true, messageObject);
					}
					isMisRequired = false;
					return false;
				}
				logger.info("Long {} message found of different type. Hence skipping this message {}",
						messageObject.getMessageType(), messageObject);
				// Add-On new line for respective Description
				messageObject.setErrorCode(ErrorCodesEnum.INVALID_MESSAGE_TYPE.getErrorCode());
				// Replace from "MESSAGE_TYPE_NOT_SUPPORTED" to dynamically get from ERRORCODE
				// enum(Aman)
				messageObject.setErrorDesc(ErrorCodesEnum.INVALID_MESSAGE_TYPE.getErrorDesc());
				// Replace from "FAILED" to dynamically get FAILED STATUS(AMAN)
				messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED);
				isMisRequired = true;
				return true;
			}
			messageObject.setFullMsg(messageObject.getMessage());
			messageObject.setIsMultiPart("N");
			if (continueMessageProcessing)
				deductCreditAndSendMessage(messageObject, messageSendingRetryCount, continueMessageProcessing, userName,
						user, selectedKannel);
			isMisRequired = true;
		}
		return isMisRequired;
	}

	private boolean deductCreditAndSendMessage(MessageObject messageObject, int messageSendingRetryCount,
			boolean continueMessageProcessing, String userName, NgUser user, NgKannelInfo selectedKannel)
			throws Exception {
		try {
			// Credit check for prepaid account.
			continueMessageProcessing = checkCreditForPrepaidAccount(messageObject, userName, user,
					continueMessageProcessing);

			if (messageObject.getRetryCount() == null)
				messageObject.setRetryCount(0);

			messageObject.setKannelName(selectedKannel.getKannelName());

			if (continueMessageProcessing)
				sendMessageToKannel(messageObject, selectedKannel);

			if (continueMessageProcessing && messageObject.getRetryCount() < 1)
				continueMessageProcessing = deductCreditForPrepaidAccount(messageObject, userName, user,
						continueMessageProcessing);
			messageObject.setSentTime(new Timestamp((new Date()).getTime()));
			return true;
		} catch (IOException e) {
			logger.error("Error while sending message to Kannel {}. Hence not retrying the message ",
					selectedKannel.getKannelName());
			e.printStackTrace();
			if (messageSendingRetryCount < 2) {
				NgKannelInfo selectedFailOverKannel = selectFailOverKannel(messageObject, ++messageSendingRetryCount,
						continueMessageProcessing, userName, user, true);

				if (selectedFailOverKannel != null) {
					messageObject.setKannelName(selectedFailOverKannel.getKannelName());
					sendMessageToKannel(messageObject, selectedFailOverKannel);
					// selectKannelAndSendMessage(messageObject,++messageSendingRetryCount,
					// continueMessageProcessing, userName, user, true); }

					// Credit deduction for prepaid account.
					if (continueMessageProcessing && messageObject.getRetryCount().intValue() < 1)
						continueMessageProcessing = deductCreditForPrepaidAccount(messageObject, userName, user,
								continueMessageProcessing);
					messageObject.setSentTime(new Timestamp((new Date()).getTime()));
					return true;
				}
				logger.error("No Kannel Found for Retry. Hence skipping the message and saving in database.");
				messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED); // Replace from "FAILED" to dynamically get
																			// FAILED status(Aman)
				messageObject.setErrorCode(ErrorCodesEnum.KANNEL_NOT_REACHABLE.getErrorCode());
				messageObject.setErrorDesc(ErrorCodesEnum.KANNEL_NOT_REACHABLE.getErrorDesc());
				continueMessageProcessing = false;
				return false;
			}

			if (messageSendingRetryCount == 2) {
				logger.error("MAX Retry Limit exceeds. Hence skipping the message and saving in database.");
				messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED);
				messageObject.setErrorCode(ErrorCodesEnum.KANNEL_NOT_REACHABLE.getErrorCode());
				messageObject.setErrorDesc(ErrorCodesEnum.KANNEL_NOT_REACHABLE.getErrorDesc());
				continueMessageProcessing = false;
				return false;
			}
			return true;
		}
	}

	private boolean validateMessageTemplate(MessageObject messageObject, NgKannelInfo selectedKannel,
			Boolean continueMessageProcessing, Boolean isMisRequired, NgUser user)
			throws UnsupportedEncodingException, DecoderException {
		boolean templateMatched = false;
		Set<String> templateTextListForSenderId = staticDataService
				.getTemplateTextListForUserAndSenderId(user.getId() + "#" + messageObject.getSenderId());

		if (templateTextListForSenderId != null || messageObject.getTemplateId() != null) {
			for (String patternTemplateAndDltTemplateId : templateTextListForSenderId) {
				logger.info("Pattern from list: {}", patternTemplateAndDltTemplateId);
				String[] tempArray = patternTemplateAndDltTemplateId.split("!!~~!!");
				String patternTemplate = tempArray[0].replace("{#var#}", "(.*)");
				Pattern pattern = Pattern.compile(patternTemplate);

				String messageTextReceived = messageObject.getMessage();
				if (messageObject.getMessageType().equalsIgnoreCase("UC"))
					messageTextReceived = new String(Hex.decodeHex(messageObject.getMessage().toCharArray()),
							"UTF-16BE");
				Matcher matcher = pattern.matcher(messageTextReceived);
				boolean matchResult = matcher.matches();
				logger.info("SenderId {} Template text is: {} ##and## message text is: {} and match result is :{}",
						messageObject.getSenderId(), patternTemplate, messageObject.getMessage(), matchResult);

				if (matchResult) {
					templateMatched = true;
					if (messageObject.getTemplateId() == null || messageObject.getTemplateId().length() == 0)
						messageObject.setTemplateId(tempArray[1]);
					logger.info("Pattern matched {}. Going to return true. ", patternTemplateAndDltTemplateId);
					break;
				}
			}
			if (!templateMatched) {
				messageObject.setStatus(Constants.MESSAGE_STATUS_REJECTED);
				messageObject.setErrorCode(ErrorCodesEnum.CONTENT_TEMPLATE_MISMATCH.getErrorCode());
				messageObject.setErrorDesc(ErrorCodesEnum.CONTENT_TEMPLATE_MISMATCH.getErrorDesc());
				try {
					sendRejectedDlr(messageObject);
				} catch (IOException e) {
					logger.error("Error while sending DLT rejected DLR.");
					e.printStackTrace();
				}
				continueMessageProcessing = false;
				isMisRequired = true;
				return false;
			}
		}
		return true;
	}

	public static int getRandomNumberInRange(int min, int max) {
		if (min >= max)
			throw new IllegalArgumentException("max must be greater than min");
		Random r = new Random();
		return r.nextInt(max - min + 1) + min;
	}

	public String sendMessageToKannel(MessageObject messageObject, NgKannelInfo selectedKannel) throws IOException {
		String kannelUrl = "http://52.66.156.150:13013/cgi-bin/sendsms?username=test&password=test&smscid=test&from=%SENDERID%&to=%TO%&text=%TEXT%&coding=0&dlr-mask=23&udh=%UDH%&dlr-url=%DLRURL%&meta-data=SMPP&entityid=%ENTITYID%&templateid=%TEMPLATEID%";
		String dlrUrl = "http://localhost:8080/dlrlistener-0.0.1-SNAPSHOT/?dr=%a&smscid=%i&statuscd=%d&uniqID=%MSGID%&customerref=%USERNAME%&receivetime=%RCVTIME%&dlrtype=9&mobile=%TO%&submittime=%SENTTIME%&expiry=12&senderid=%SENDERID%&carrierid=%CARRIERID%&circleid=%CIRCLEID%&routeid=%ROUTEID%&systemid=%SRCSYSTEM%msgtxt=%MSGTXT%&currkannelid=%CURRKANNELID%&reqid=%REQID%&retrycount=%RETRYCOUNT%&usedkannelid=%USEDKANNELID%&ismultipart=%ISMULTIPART%&msgtype=%MSGTYPE%&splitcount=%SPLITCOUNT%";

		kannelUrl = selectedKannel.getKannelUrl();
		kannelUrl = kannelUrl.replaceAll("%SENDERID%", URLEncoder.encode(messageObject.getSenderId(), "UTF-8"));
		kannelUrl = kannelUrl.replaceAll("%TO%", URLEncoder.encode(messageObject.getDestNumber(), "UTF-8"));

		if (messageObject.getMessageType() != null
				&& (messageObject.getMessageType().equals("UC") || messageObject.getMessageType().equals("FU"))) {
			byte[] byteArray = hexToByteArray(messageObject.getMessage());
			String convertedTextUtf8 = new String(byteArray, "UTF-16BE");
			String finalText = UriUtils.encode(convertedTextUtf8, "UTF-16BE");

			logger.debug("Final Unicode Text: " + finalText);
			kannelUrl = kannelUrl.replaceAll("%TEXT%", finalText);
			kannelUrl = kannelUrl.replaceAll("%CODING%", "2");
		} else {
			kannelUrl = kannelUrl.replaceAll("%TEXT%", URLEncoder.encode(messageObject.getMessage(), "UTF-8"));
			kannelUrl = kannelUrl.replaceAll("%CODING%", "0");
		}
		if (messageObject.getUdh() != null) {
			String convertedUdh = getConvertedString(messageObject.getUdh());
			kannelUrl = kannelUrl.replaceAll("%UDH%", convertedUdh);
		} else {
			kannelUrl = kannelUrl.replaceAll("&udh=%UDH%", "");
		}
		if (messageObject.getEntityId() != null) {
			kannelUrl = kannelUrl.replaceAll("%ENTITYID%", URLEncoder.encode(messageObject.getEntityId(), "UTF-8"));
		} else {
			kannelUrl = kannelUrl.replaceAll("%ENTITYID%", "");
		}
		if (messageObject.getTemplateId() != null) {
			kannelUrl = kannelUrl.replaceAll("%TEMPLATEID%", URLEncoder.encode(messageObject.getTemplateId(), "UTF-8"));
		} else {
			kannelUrl = kannelUrl.replaceAll("%TEMPLATEID%", "");
		}
		if (messageObject.getExpiryTime() != null) {
			kannelUrl = kannelUrl.replaceAll("%VALIDITY%", URLEncoder.encode(messageObject.getExpiryTime(), "UTF-8"));
		} else {
			kannelUrl = kannelUrl.replaceAll("%VALIDITY%", "");
		}
		if (messageObject.getMessageType().equals("FL") || messageObject.getMessageType().equals("FU"))
			kannelUrl = String.valueOf(kannelUrl) + "&mclass=0";

		dlrUrl = selectedKannel.getDlrUrl();
		if (messageObject.getMessageId() != null)
			dlrUrl = dlrUrl.replaceAll("%MSGID%", messageObject.getMessageId());
		if (messageObject.getUsername() != null)
			dlrUrl = dlrUrl.replaceAll("%USERNAME%", messageObject.getUsername());
		if (messageObject.getOrgMessage() != null)
			dlrUrl = dlrUrl.replaceAll("%MSGTXT%", URLEncoder.encode(messageObject.getOrgMessage(), "UTF-8"));

		dlrUrl = dlrUrl.replace("%CURRKANNELID%", "" + selectedKannel.getId());
		if (messageObject.getRequestId() != null)
			dlrUrl = dlrUrl.replace("%REQID%", URLEncoder.encode(messageObject.getRequestId(), "UTF-8"));
		if (messageObject.getIsMultiPart() != null)
			dlrUrl = dlrUrl.replace("%ISMULTIPART%", messageObject.getIsMultiPart());
		if (messageObject.getUsedKannelIds() != null) {
			dlrUrl = dlrUrl.replace("%USEDKANNELID%", URLEncoder.encode(messageObject.getUsedKannelIds(), "UTF-8"));
		} else {
			dlrUrl = dlrUrl.replace("%USEDKANNELID%", "0");
		}

		if (messageObject.getMessageType() != null)
			dlrUrl = dlrUrl.replace("%MSGTYPE%", messageObject.getMessageType());
		if (messageObject.getRetryCount() != null) {
			dlrUrl = dlrUrl.replace("%RETRYCOUNT%", "" + messageObject.getRetryCount());
		} else {
			dlrUrl = dlrUrl.replace("%RETRYCOUNT%", "0");
		}
		if (messageObject.getSplitCount() != null && messageObject.getSplitCount().intValue() > 1) {
			dlrUrl = dlrUrl.replace("%SPLITCOUNT%", "" + messageObject.getSplitCount());
		} else {
			dlrUrl = dlrUrl.replace("%SPLITCOUNT%", "1");
		}

//		if (messageObject.getReceiveTime() != null)
//			dlrUrl = dlrUrl.replaceAll("%RCVTIME%", Long.toString(messageObject.getReceiveTime().getTime()));
		if (messageObject.getReceiveTime() != null) {
			long timeInMillis = messageObject.getReceiveTime().getTime();
			long timeInSec = (timeInMillis / 1000) * 1000;
			dlrUrl = dlrUrl.replaceAll("%RCVTIME%", Long.toString(timeInSec));
		}

		String encodeDestNum = URLEncoder.encode(messageObject.getDestNumber(), StandardCharsets.UTF_8.toString());
		encodeDestNum = encodeDestNum.replace("+", "%20");
		if (messageObject.getDestNumber() != null)
			dlrUrl = dlrUrl.replaceAll("%TO%", encodeDestNum);

//		messageObject.setSentTime(new Timestamp((new Date()).getTime()));
//		if (messageObject.getSentTime() != null)
//			dlrUrl = dlrUrl.replaceAll("%SENTTIME%", Long.toString(messageObject.getSentTime().getTime()));
		
		messageObject.setSentTime(new Timestamp(System.currentTimeMillis()));
		if (messageObject.getSentTime() != null) {
			long timeInMillis = messageObject.getSentTime().getTime();
			long timeInSec = (timeInMillis / 1000) * 1000;
			dlrUrl = dlrUrl.replaceAll("%SENTTIME%", Long.toString(timeInSec));
		}

		if (messageObject.getOriginalSenderId() != null) {
			dlrUrl = dlrUrl.replaceAll("%SENDERID%", messageObject.getOriginalSenderId());
		} else if (messageObject.getSenderId() != null) {
			dlrUrl = dlrUrl.replaceAll("%SENDERID%", messageObject.getSenderId());
		}
		messageObject.setKannelId(selectedKannel.getId());
		if (messageObject.getKannelId() != null)
			dlrUrl = dlrUrl.replaceAll("%KANNELID%", messageObject.getKannelId().toString());
		if (messageObject.getCarrierId() != null) {
			dlrUrl = dlrUrl.replaceAll("%CARRIERID%", messageObject.getCarrierId().toString());
		} else {
			dlrUrl = dlrUrl.replaceAll("%CARRIERID%", "0");
		}
		if (messageObject.getCircleId() != null) {
			dlrUrl = dlrUrl.replaceAll("%CIRCLEID%", messageObject.getCircleId().toString());
		} else {
			dlrUrl = dlrUrl.replaceAll("%CIRCLEID%", "0");
		}

		if (messageObject.getRouteId() != null)
			dlrUrl = dlrUrl.replaceAll("%ROUTEID%", messageObject.getRouteId().toString());
		if (messageObject.getInstanceId() != null)
			dlrUrl = dlrUrl.replaceAll("%SRCSYSTEM%", messageObject.getInstanceId());
		if (messageObject.getCustRef() != null) {
			dlrUrl = dlrUrl.replaceAll("%CUSTREF%", URLEncoder.encode(messageObject.getCustRef(), "UTF-8"));
		} else {
			dlrUrl = dlrUrl.replaceAll("%CUSTREF%", "");
		}

		if (messageObject.getTemplateId() != null) {
			dlrUrl = dlrUrl.replaceAll("%TEMPLATEID%",
					URLEncoder.encode(messageObject.getTemplateId(), StandardCharsets.UTF_8.toString()));
		} else {
			dlrUrl = dlrUrl.replaceAll("%TEMPLATEID%", "");
		}

		if (messageObject.getEntityId() != null) {
			dlrUrl = dlrUrl.replaceAll("%ENTITYID%",
					URLEncoder.encode(messageObject.getEntityId(), StandardCharsets.UTF_8.toString()));
		} else {
			dlrUrl = dlrUrl.replaceAll("%ENTITYID%", "");
		}

		kannelUrl = kannelUrl.replaceAll("%DLRURL%", URLEncoder.encode(dlrUrl, "UTF-8"));
		logger.info("final kannel URL Without Encoding: " + kannelUrl);
		logger.info("Final DLR URL Without Encoding: " + dlrUrl);

		URL obj = new URL(kannelUrl);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();
		con.setRequestMethod("GET");
		int responseCode = con.getResponseCode();
		logger.info("Response from kannel received : " + responseCode);
		messageObject.setStatus("SUBMITTED");

		BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		StringBuffer response = new StringBuffer();
		String inputLine;
		while ((inputLine = in.readLine()) != null)
			response.append(inputLine);
		in.close();
		logger.info("Response string from kannel is : ", response.toString());
		return response.toString();
	}

	private static final char[] hexChar = new char[] { '0', '1', '2', '3', '4', '5', '6', '7', '8', '9', 'a', 'b', 'c',
			'd', 'e', 'f' };

	public static byte[] convertHexStringToBytes(String hexString, int offset, int endIndex) {
		byte[] data;
		String realHexString = hexString.substring(offset, endIndex).toLowerCase();

		if (realHexString.length() % 2 == 0) {
			data = new byte[realHexString.length() / 2];
		} else {
			data = new byte[(int) Math.ceil(realHexString.length() / 2.0D)];
		}
		byte temp = 0;
		int j = 0;
		int i;
		for (i = 0; i < realHexString.length(); i += 2) {
			char[] tmp;
			try {
				tmp = realHexString.substring(i, i + 2).toCharArray();
			} catch (StringIndexOutOfBoundsException siob) {
				tmp = (new StringBuilder(String.valueOf(realHexString.substring(i)))).append("0").toString()
						.toCharArray();
			}
			data[j] = (byte) ((Arrays.binarySearch(hexChar, tmp[0]) & 0xF) << 4);
			System.out.println(data[j]);
			temp = (byte) (temp | (byte) (Arrays.binarySearch(hexChar, tmp[1]) & 0xF));
			data[j++] = temp;
			System.out.println(temp);
		}
		for (i = realHexString.length(); i > 0; i -= 2)
			;
		return data;
	}

	public static byte[] convertHexStringToBytes(String hexString) {
		return convertHexStringToBytes(hexString, 0, hexString.length());
	}

	public static String pmToHexString(String msg) throws Exception {
		byte[] byteArr = null;
		byteArr = msg.getBytes();
		StringBuffer sb = new StringBuffer(byteArr.length * 2);
		for (int i = 0; i < byteArr.length; i++) {
			int v = byteArr[i] & 0xFF;
			if (v < 16)
				sb.append('0');
			sb.append(Integer.toHexString(v));
		}
		return sb.toString().toUpperCase();
	}

	public static String getConvertedString(String pStr) {
		String convertedStr = "%";
		try {
			for (int loop = 0; loop < pStr.length(); loop += 2) {
				if (loop == pStr.length() - 2) {
					convertedStr = convertedStr + pStr.substring(loop, loop + 2);
				} else {
					convertedStr = convertedStr + pStr.substring(loop, loop + 2) + "%";
				}
			}
			return convertedStr;
		} catch (Exception ex) {
			return null;
		}
	}

	private boolean deductCreditForPrepaidAccount(MessageObject messageObject, String userName, NgUser user,
			boolean continueMessageProcessing) {
		if (user != null && user.getNgBillingType().getId() == 1) {
			Integer userCredit = userCreditMapService.getUserCreditByUserNameFromRedis(userName);
			logger.info("Get available user credit before deduction from redis for user {} is {}", userName,
					userCredit);
			if (userCredit != null && userCredit <= 0) {
				logger.info("Insufficient Account Balance for user: " + user.getUserName());
				messageObject.setErrorCode(ErrorCodesEnum.INSUFFICIENT_BALANCE.getErrorCode());
				messageObject.setErrorDesc(ErrorCodesEnum.INSUFFICIENT_BALANCE.getErrorDesc());
				messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED);
				continueMessageProcessing = false;
				return false;
			}
			userCredit = userCreditMapService.updateUserCacheCreditByUserNameInCache(userName, Integer.valueOf(-1));
			logger.info("Available user credit after deduction for user {} is {}", userName, userCredit);
		} else {
			logger.info("{} is a Postpaid User. Hence no need of credit check.", userName);
		}
		return true;
	}

	private boolean checkCreditForPrepaidAccount(MessageObject messageObject, String userName, NgUser user,
			boolean continueMessageProcessing) {
		if (user != null && user.getNgBillingType().getId() == 1) {
			Integer userCredit = userCreditMapService.getUserCreditByUserNameFromRedis(userName);
			logger.info(
					"Checking available user credit before deduction from redis for user {} is {} and messageId {} ",
					userName, userCredit, messageObject.getMessageId());
			if (userCredit != null && userCredit <= 0) {
				logger.info("Insufficient Account Balance for user: " + user.getUserName());
				messageObject.setErrorCode(ErrorCodesEnum.INSUFFICIENT_BALANCE.getErrorCode());
				messageObject.setErrorDesc(ErrorCodesEnum.INSUFFICIENT_BALANCE.getErrorDesc());
				messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED);
				continueMessageProcessing = false;
				return false;
			}
		} else {
			logger.info("{} is a Postpaid User. Hence no need of credit check.", userName);
		}
		return true;
	}

	public String convertMessageObjectToMisObjectAsString(MessageObject messageObject)
			throws UnsupportedEncodingException, DecoderException {
		NgMisMessage ngMisMessage = new NgMisMessage();
		ngMisMessage.setAckId(messageObject.getRequestId());
		ngMisMessage.setCarrierId(messageObject.getCarrierId());
		ngMisMessage.setCircleId(messageObject.getCircleId());
		ngMisMessage.setErrorCode(messageObject.getErrorCode());
		ngMisMessage.setErrorDesc(messageObject.getErrorDesc());
		ngMisMessage.setFullMsg(messageObject.getFullMsg());
		ngMisMessage.setGroupId(messageObject.getGroupId());
		ngMisMessage.setKannelId(messageObject.getKannelId());
		ngMisMessage.setMessageClass(messageObject.getMessageClass());
		ngMisMessage.setMessageId(messageObject.getMessageId());
		ngMisMessage.setMessageSource(messageObject.getInstanceId());
		ngMisMessage.setMessageText(messageObject.getMessage());
		ngMisMessage.setMessageType(messageObject.getMessageType());
		ngMisMessage.setMobileNumber(messageObject.getDestNumber());
		ngMisMessage.setOriginalMessageId(messageObject.getMessageId());
		ngMisMessage.setPriority(messageObject.getPriority());
		ngMisMessage.setReceivedTs(messageObject.getReceiveTime());
		ngMisMessage.setRouteId(messageObject.getRouteId());
		ngMisMessage.setSenderId(messageObject.getSenderId());
		Date currentTime = new Date();
		long seconds = currentTime.getTime() / 1000L;
		Timestamp timestamp = new Timestamp(seconds * 1000L);
		ngMisMessage.setSentTs(timestamp);
		ngMisMessage.setSplitCount(messageObject.getSplitCount());
		ngMisMessage.setStatus(messageObject.getStatus());
		ngMisMessage.setUdh(messageObject.getUdh());
		ngMisMessage.setUserId(messageObject.getUserId());
		ngMisMessage.setFailedRetryCount(messageObject.getRetryCount());
		ngMisMessage.setEntityId(messageObject.getEntityId());
		ngMisMessage.setTemplateId(messageObject.getTemplateId());
		ngMisMessage.setCampaignId(messageObject.getCampaignId());
		ngMisMessage.setCampaignName(messageObject.getCampaignName());
		ngMisMessage.setCampaignTime(messageObject.getCampaignTime());
		ngMisMessage.setKannelName(messageObject.getKannelName());
		String hexMessageText = ngMisMessage.getFullMsg();
		if (messageObject.getCircleId() != null && messageObject.getCarrierId() != null) {
			String carrierName = staticDataService.getCarrierNameById(messageObject.getCarrierId());
			String circleName = staticDataService.getCircleNameById(messageObject.getCircleId());
			ngMisMessage.setCarrierName(carrierName);
			ngMisMessage.setCircleName(circleName);
		}
		if (messageObject.getMessageType().equalsIgnoreCase("UC")) {
			String msgText = new String(Hex.decodeHex(messageObject.getMessage().toCharArray()), "UTF-16BE");
			ngMisMessage.setFullMsg(msgText);
		}
		try {
			return objectMapper.writeValueAsString(ngMisMessage);
		} catch (JsonProcessingException e) {
			e.printStackTrace();
			return null;
		}
	}

	public static byte[] hexStringToByteArray(String s) {
		int len = s.length();
		byte[] data = new byte[len / 2];
		for (int i = 0; i < len; i += 2)
			data[i / 2] = (byte) ((Character.digit(s.charAt(i), 16) << 4) + Character.digit(s.charAt(i + 1), 16));
		return data;
	}

	public static byte[] hexToByteArray(String hex) {
		hex = (hex.length() % 2 != 0) ? ("0" + hex) : hex;
		byte[] b = new byte[hex.length() / 2];
		for (int i = 0; i < b.length; i++) {
			int index = i * 2;
			int v = Integer.parseInt(hex.substring(index, index + 2), 16);
			b[i] = (byte) v;
		}
		return b;
	}

//	private static final char[] hexCode = "0123456789ABCDEF".toCharArray();

	public static String convertByteArrayToHexString(byte[] data) {
		char[] hexCode = "0123456789ABCDEFghijklmnopqrstuvwxyz".toCharArray();
		StringBuilder r = new StringBuilder(data.length * 2);
		for (byte b : data) {
			r.append(hexCode[b >> 4 & 0xF]);
			r.append(hexCode[b & 0xF]);
		}
		return r.toString();
	}

	private NgKannelInfo selectFailOverKannel(MessageObject messageObject, int messageSendingRetryCount,
			boolean continueMessageProcessing, String userName, NgUser user, boolean isMisRequired) throws Exception {
		String[] kannelIdsArray;

		if (messageSendingRetryCount > 1) {
			logger.error("Message {} got failed as Kannel {} was not reachable. Going to try from failover group ",
					messageObject.getMessageId(), messageObject.getKannelId());
			ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
					"Message will try from fail over group as kannel was not reachable. Original group was "
							+ messageObject.getGroupId() + " and kannel was " + messageObject.getKannelId());
			messageObject.setGroupId(staticDataService.getFailOverRoutingGroup(messageObject.getGroupId()));
		}
		
		String kannelIds = staticDataService
				.getKannelIdsForGroupId(Integer.toString(messageObject.getGroupId().intValue()));
		
		logger.info("Kannel ids for group id {} are {}", messageObject.getGroupId(), kannelIds);
		if (kannelIds.contains("#")) {
			kannelIdsArray = kannelIds.split("#");
		} else {
			kannelIdsArray = new String[] { kannelIds };
		}
		logger.info("Kannel ids array from cache size is: " + kannelIdsArray.length);
		
		
		int selectedKannelIndex = 0;
		if (kannelIdsArray != null) {
			List<NgKannelInfo> activeKannelInfoList = kannelInfoService
					.removeInActiveKannelsFromArray(kannelIdsArray);
			if (activeKannelInfoList == null || (activeKannelInfoList != null && activeKannelInfoList.size() == 0)) {
				ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
						"Message will try from fail over group as no active kannel was found in group. Original group was "
								+ messageObject.getGroupId());
				messageObject.setGroupId(staticDataService.getFailOverRoutingGroup(messageObject.getGroupId()));
				kannelIds = staticDataService
						.getKannelIdsForGroupId(Integer.toString(messageObject.getGroupId()));
				logger.info("Kannel ids for group id {} are {}", messageObject.getGroupId(), kannelIds);
				
				if (kannelIds.contains("#")) {
					kannelIdsArray = kannelIds.split("#");
				} else {
					kannelIdsArray = new String[] { kannelIds };
				}
				
				if (kannelIdsArray != null) {
					activeKannelInfoList = kannelInfoService.removeInActiveKannelsFromArray(kannelIdsArray);
					if (activeKannelInfoList == null
							|| (activeKannelInfoList != null && activeKannelInfoList.size() == 0)) {
						ngNotesService.createAndUpdateMisNotes(messageObject.getMessageId(),
								"Message will be skipped as no active kannel was found in fail over group. Faileover group was "
										+ messageObject.getGroupId());
						messageObject.setStatus(Constants.MESSAGE_STATUS_FAILED);
						messageObject.setErrorCode(ErrorCodesEnum.NO_ACTIVE_KANNEL_FOUND.getErrorCode());
						messageObject.setErrorCode(ErrorCodesEnum.NO_ACTIVE_KANNEL_FOUND.getErrorDesc());
						continueMessageProcessing = false;
						return null;
					}
				}
			}
			if (activeKannelInfoList != null && activeKannelInfoList.size() > 1)
				selectedKannelIndex = getRandomNumberInRange(0, activeKannelInfoList.size() - 1);
			NgKannelInfo selectedKannel = activeKannelInfoList.get(selectedKannelIndex);
			logger.info("Selected Kannel Id is :" + selectedKannel.getId());
			messageObject.setKannelId(selectedKannel.getId());
			return selectedKannel;
		}
		return null;
	}

	public static void main(String[] args) throws Exception {
		String hex = "abcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuvwxyzabcdefghijklmnopqrstuv[";
		System.out.println("Hex length: " + hex.length());
		SplittedMessage sm = MessageSubmitterUtil.splitMessage(hex.getBytes(), Integer.valueOf(153), "PM");
		byte[][] byteMessagesArray = sm.getByteMessagesArray();
		byte[][] byteUdhArray = sm.getByteUdhArray();
		logger.info("***** Important Check. Dont Ignore ******");
		int i;
		for (i = 0; i < byteMessagesArray.length; i++) {
			String hexStringToSend = convertByteArrayToHexString(byteMessagesArray[i]);
			logger.info("Splitted part [{}] message hex string is : {}", Integer.valueOf(i), hexStringToSend);
			String convertedTextUtf8 = new String(byteMessagesArray[i]);
			System.out.println("convertedTextUtf8:" + convertedTextUtf8);
			String str1 = UriUtils.encode(convertedTextUtf8, "UTF-16BE");
		}
		for (i = 0; i < 10; i++)
			System.out.println(getRandomNumberInRange(0, 9));
	}

	public String sendRejectedDlr(MessageObject messageObject) throws IOException {
		NgKannelInfo ngKannelInfo = kannelInfoService.getDefaultKannelInfoForRejectedMessage();
		String dlrListenerUrl = ngKannelInfo.getDlrUrl();

		String dr = "id:%ID% sub:%ERRORCODE% dlvrd:%DLVRD% submit date:%SUBDATE% done date:%DONEDATE% stat:%STAT% err:%ERRORCODE% text:%TEXT%";

		if (messageObject.getErrorCode().equals(ErrorCodesEnum.FAILED_NUMBER_FOUND.getErrorCode())) {
			dr = dr.replaceAll("%ERRORCODE%", "882");
			dr = dr.replaceAll("%DLVRD%", "001");
			dr = dr.replaceAll("%STAT%", Constants.MESSAGE_STATUS_FAILED);

			messageObject.setErrorCode("");
			messageObject.setErrorDesc("");
			messageObject.setStatus("");
		} else {
			dr = dr.replaceAll("%ERRORCODE%", messageObject.getErrorCode());
			dr = dr.replaceAll("%DLVRD%", "001");
			dr = dr.replaceAll("%STAT%", Constants.MESSAGE_STATUS_REJECTED);
		}

		dr = dr.replaceAll("%ID%", System.currentTimeMillis()
				+ messageObject.getDestNumber().substring(5, messageObject.getDestNumber().length() - 1));
		if (messageObject.getMessage() != null && messageObject.getMessage().length() > 10) {
			dr = dr.replaceAll("%TEXT%", messageObject.getMessage().substring(0, 9));
		} else {
			dr = dr.replaceAll("%TEXT%", messageObject.getMessage());
		}
		SimpleDateFormat dateFormat = new SimpleDateFormat("yyMMddHHmmss");
		String date = dateFormat.format(new Date());
		dr = dr.replaceAll("%SUBDATE%", date);
		dr = dr.replaceAll("%DONEDATE%", date);

		dr = URLEncoder.encode(dr, "UTF-8");

		dlrListenerUrl = dlrListenerUrl.replaceFirst("%a", dr);
		dlrListenerUrl = dlrListenerUrl.replaceFirst("smscid=%i", "smscid=000");
		dlrListenerUrl = dlrListenerUrl.replaceFirst("statuscd=%d", "statuscd=000");

		if (messageObject.getMessageId() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%MSGID%", messageObject.getMessageId());

		if (messageObject.getUsername() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%USERNAME%", messageObject.getUsername());

		if (messageObject.getMessage() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%MSGTXT%",
					URLEncoder.encode(messageObject.getMessage(), "UTF-8"));

		dlrListenerUrl = dlrListenerUrl.replace("%CURRKANNELID%", "0");

		if (messageObject.getRequestId() != null)
			dlrListenerUrl = dlrListenerUrl.replace("%REQID%",
					URLEncoder.encode(messageObject.getRequestId(), "UTF-8"));

		Integer messageSplitCount = messageObject.getSplitCount();

		if ((messageSplitCount != null && messageSplitCount.intValue() > 1)
				|| (messageObject.getIsMultiPart() != null && messageObject.getIsMultiPart().equals("Y"))) {
			messageObject.setIsMultiPart("Y");
			dlrListenerUrl = dlrListenerUrl.replace("%ISMULTIPART%", messageObject.getIsMultiPart());
		} else {
			messageObject.setIsMultiPart("N");
			dlrListenerUrl = dlrListenerUrl.replace("%ISMULTIPART%", messageObject.getIsMultiPart());
		}

		if (messageObject.getUsedKannelIds() != null) {
			dlrListenerUrl = dlrListenerUrl.replace("%USEDKANNELID%",
					URLEncoder.encode(messageObject.getUsedKannelIds(), "UTF-8"));
		} else {
			dlrListenerUrl = dlrListenerUrl.replace("%USEDKANNELID%", "0");
		}

		if (messageObject.getMessageType() != null)
			dlrListenerUrl = dlrListenerUrl.replace("%MSGTYPE%", messageObject.getMessageType());

		if (messageObject.getRetryCount() != null) {
			dlrListenerUrl = dlrListenerUrl.replace("%RETRYCOUNT%", "" + messageObject.getRetryCount());
		} else {
			dlrListenerUrl = dlrListenerUrl.replace("%RETRYCOUNT%", "0");
		}

		if (messageObject.getSplitCount() != null && messageObject.getSplitCount().intValue() > 1) {
			dlrListenerUrl = dlrListenerUrl.replace("%SPLITCOUNT%", "" + messageObject.getSplitCount());
		} else {
			dlrListenerUrl = dlrListenerUrl.replace("%SPLITCOUNT%", "1");
		}

		if (messageObject.getCustRef() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CUSTREF%",
					URLEncoder.encode(messageObject.getCustRef(), "UTF-8"));
		} else {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CUSTREF%", "");
		}

		if (messageObject.getReceiveTime() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%RCVTIME%",
					Long.toString(messageObject.getReceiveTime().getTime()));

		if (messageObject.getDestNumber() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%TO%", messageObject.getDestNumber());
		messageObject.setSentTime(new Timestamp((new Date()).getTime()));

		if (messageObject.getSentTime() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%SENTTIME%",
					Long.toString(messageObject.getSentTime().getTime()));

		if (messageObject.getOriginalSenderId() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%SENDERID%", messageObject.getOriginalSenderId());
		} else if (messageObject.getSenderId() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%SENDERID%", messageObject.getSenderId());
		}

		messageObject.setKannelId(Integer.valueOf(0));

		if (messageObject.getKannelId() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%KANNELID%", messageObject.getKannelId().toString());

		if (messageObject.getCarrierId() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CARRIERID%", messageObject.getCarrierId().toString());
		} else {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CARRIERID%", "0");
		}

		if (messageObject.getCircleId() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CIRCLEID%", messageObject.getCircleId().toString());
		} else {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%CIRCLEID%", "0");
		}

		if (messageObject.getRouteId() != null) {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%ROUTEID%", messageObject.getRouteId().toString());
		} else {
			dlrListenerUrl = dlrListenerUrl.replaceAll("%ROUTEID%", "0");
		}

		if (messageObject.getInstanceId() != null)
			dlrListenerUrl = dlrListenerUrl.replaceAll("%SRCSYSTEM%", messageObject.getInstanceId());
		logger.info("final dlr listener url for rejected message : " + dlrListenerUrl);

		dlrListenerUrl = dlrListenerUrl.replaceAll("\\+", "%20");
		dlrListenerUrl = dlrListenerUrl.replaceAll(" ", "%20");

		URL obj = new URL(dlrListenerUrl);
		HttpURLConnection con = (HttpURLConnection) obj.openConnection();
		con.setRequestMethod("GET");
		int responseCode = con.getResponseCode();
		logger.info("Response from dlr listener received : " + responseCode);
		BufferedReader in = new BufferedReader(new InputStreamReader(con.getInputStream()));
		StringBuffer response = new StringBuffer();
		String inputLine;
		while ((inputLine = in.readLine()) != null)
			response.append(inputLine);
		in.close();
		logger.info("Response string from kannel is : ", response.toString());
		return response.toString();
	}
}
