package com.noesis.submitter;

import java.util.Random;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

public class MessageSubmitterUtil {

	private static final Logger logger = LogManager.getLogger(MessageSubmitter.class);

	private static final Object[][] gsmExtensionCharacters = new Object[][] {

			{ "\n", new Byte((byte) 0x0a) },

			{ "^", new Byte((byte) 0x14) },

			{ " ", new Byte((byte) 0x1b) }, // reserved for future extensions

			{ "{", new Byte((byte) 0x28) },

			{ "}", new Byte((byte) 0x29) },

			{ "\\", new Byte((byte) 0x2f) },

			{ "[", new Byte((byte) 0x3c) },

			{ "~", new Byte((byte) 0x3d) },

			{ "]", new Byte((byte) 0x3e) },

			{ "|", new Byte((byte) 0x40) },

			{ "€", new Byte((byte) 0x65) }

	};

	public static byte[][] splitUnicodeMessage(byte[] aMessage, Integer maximumMultipartMessageSegmentSize) {
		byte UDHIE_HEADER_LENGTH = 0x05;
		byte UDHIE_IDENTIFIER_SAR = 0x00;
		byte UDHIE_SAR_LENGTH = 0x03;

		// determine how many messages have to be sent
		int numberOfSegments = aMessage.length / maximumMultipartMessageSegmentSize;
		int messageLength = aMessage.length;
		if (numberOfSegments > 255) {
			numberOfSegments = 255;
			messageLength = numberOfSegments * maximumMultipartMessageSegmentSize;
		}
		if (messageLength % maximumMultipartMessageSegmentSize > 0)
			numberOfSegments++;

		// prepare array for all of the msg segments
		byte[][] segments = new byte[numberOfSegments][];

		// generate new reference number
		byte[] referenceNumber = new byte[1];
		(new Random()).nextBytes(referenceNumber);
		logger.info("Final number of segments: {}", numberOfSegments);

		// split the message adding required headers
		for (int i = 0; i < numberOfSegments; i++) {
			int lengthOfData;
			if (numberOfSegments - i == 1) {
				lengthOfData = messageLength - i * maximumMultipartMessageSegmentSize;
			} else {
				lengthOfData = maximumMultipartMessageSegmentSize;
			}

			// new array to store the header
			segments[i] = new byte[lengthOfData];
			System.arraycopy(aMessage, i * maximumMultipartMessageSegmentSize, segments[i], 0, lengthOfData);
		}
		return segments;
	}

	public static SplittedMessage splitMessage(byte[] aMessage, Integer maximumMultipartMessageSegmentSize,
			String messageType) {
		SplittedMessage sm = new SplittedMessage();
		byte UDHIE_HEADER_LENGTH = 0x05;
		byte UDHIE_IDENTIFIER_SAR = 0x00;
		byte UDHIE_SAR_LENGTH = 0x03;

		int lengthOfData = 0;
		int orgMsgPartLength = maximumMultipartMessageSegmentSize;
		if (messageType.equalsIgnoreCase("PM"))
			for (byte b : aMessage) {
				if (lengthOfData < orgMsgPartLength) {
					char temp = (char) b;
					if (temp == '[' || temp == ']' || temp == '\n' || temp == '^' || temp == '{' || temp == '}'
							|| temp == '\\' || temp == '~' || temp == '|' || temp == '€') {
						Integer integer1 = maximumMultipartMessageSegmentSize,
								integer2 = maximumMultipartMessageSegmentSize = Integer
										.valueOf(maximumMultipartMessageSegmentSize - 1);
						lengthOfData++;
					}
					lengthOfData++;
				}
			}

		// determine how many messages have to be sent
		int numberOfSegments = aMessage.length / maximumMultipartMessageSegmentSize;
		int messageLength = aMessage.length;

		if (numberOfSegments > 255) {
			numberOfSegments = 255;
			messageLength = numberOfSegments * maximumMultipartMessageSegmentSize;
		}
		if (messageLength % maximumMultipartMessageSegmentSize > 0)
			numberOfSegments++;

		// prepare array for all of the msg segments
		byte[][] segments = new byte[numberOfSegments][];

		byte[][] udhSegments = new byte[numberOfSegments][];

		// generate new reference number
		byte[] referenceNumber = new byte[1];
		int randomNumber = getRandomNumberInRange(1, 126);
		referenceNumber[0] = (byte) randomNumber;

		// split the message adding required headers
		for (int i = 0; i < numberOfSegments; i++) {
			if (numberOfSegments - i == 1) {
				lengthOfData = messageLength - i * maximumMultipartMessageSegmentSize;
			} else {
				lengthOfData = maximumMultipartMessageSegmentSize;
			}

			// new array to store the header
			segments[i] = new byte[lengthOfData];
			udhSegments[i] = new byte[6];

			// UDH header
			// doesn't include itself, its header length
			udhSegments[i][0] = UDHIE_HEADER_LENGTH;

			// SAR identifier
			udhSegments[i][1] = UDHIE_IDENTIFIER_SAR;

			// SAR length
			udhSegments[i][2] = UDHIE_SAR_LENGTH;

			// reference number (same for all messages)
			udhSegments[i][3] = referenceNumber[0];

			// total number of segments
			udhSegments[i][4] = (byte) numberOfSegments;

			// segment number
			udhSegments[i][5] = (byte) (i + 1);

			// copy the data into the array
			System.arraycopy(aMessage, i * maximumMultipartMessageSegmentSize, segments[i], 0, lengthOfData);
		}
		sm.setByteMessagesArray(segments);
		sm.setByteUdhArray(udhSegments);
		return sm;
	}

	public static SplittedMessage splitPlainMessage(byte[] aMessage, Integer maximumMultipartMessageSegmentSize,
			String messageType) {

		String tempString = new String(aMessage);
		tempString = tempString.replaceAll("[\\^{}\\\\\\[~\\]|€]{1}", "`$0");
		aMessage = tempString.getBytes();
		int extendedLengthOfMessage = tempString.length();
		SplittedMessage sm = new SplittedMessage();
		byte UDHIE_HEADER_LENGTH = 0x05;
		byte UDHIE_IDENTIFIER_SAR = 0x00;
		byte UDHIE_SAR_LENGTH = 0x03;
		byte[] referenceNumber = new byte[1];
		int randomNumber = getRandomNumberInRange(1, 126);
		referenceNumber[0] = (byte) randomNumber;

		int lengthOfData = 0;
		int orgMsgPartLength = maximumMultipartMessageSegmentSize;
		int numberOfSegments = extendedLengthOfMessage / orgMsgPartLength;
		if (extendedLengthOfMessage % orgMsgPartLength > 0)
			numberOfSegments++;

		// prepare array for all of the msg segments
		byte[][] segments = new byte[numberOfSegments][];
		byte[][] udhSegments = new byte[numberOfSegments][];
		for (int i = 0; i < numberOfSegments; i++) {
			if (numberOfSegments - i == 1) {
				lengthOfData = extendedLengthOfMessage - i * maximumMultipartMessageSegmentSize;
			} else {
				lengthOfData = maximumMultipartMessageSegmentSize;
			}
			segments[i] = new byte[lengthOfData];
			udhSegments[i] = new byte[6];

			// UDH header
			// doesn't include itself, its header length
			udhSegments[i][0] = UDHIE_HEADER_LENGTH;
			// SAR identifier
			udhSegments[i][1] = UDHIE_IDENTIFIER_SAR;
			// SAR length
			udhSegments[i][2] = UDHIE_SAR_LENGTH;
			// reference number (same for all messages)
			udhSegments[i][3] = referenceNumber[0];
			// total number of segments
			udhSegments[i][4] = (byte) numberOfSegments;
			// segment number
			udhSegments[i][5] = (byte) (i + 1);

			// copy the data into the array
			System.arraycopy(tempString.getBytes(), i * maximumMultipartMessageSegmentSize, segments[i], 0,
					lengthOfData);
			String s = new String(segments[i]);
			s = s.replace("`", "");
			segments[i] = s.getBytes();
		}
		sm.setByteMessagesArray(segments);
		sm.setByteUdhArray(udhSegments);
		return sm;
	}

	public static void main(String[] args) throws Exception {
		String orgMessage = "Dear Parents, Your Ward AHSAN NOORANI, COMP-ID=34, ADM-NO=267, Result For WR-I [ENG 57/80] [HIN 48/80] [MAT 66/80] [SCI 46/80] [SOC 47/80] [GK 58/100] [Total : 480/600 [Result:Failed].";
		orgMessage = "Client Code 7AAG570 Arcadia Trade Confirm*F&O* 16.06.20 * B NIFTY206189700CE +225  187.88| B NIFTY206189700PE +225 @ 22.57| B NIFTY20JUN10100CE +75 @|";
		SplittedMessage sm = splitPlainMessage(orgMessage.getBytes(), Integer.valueOf(153), "PM");
		byte[][] byteMessagesArray = sm.getByteMessagesArray();
		byte[][] byteUdhArray = sm.getByteUdhArray();
		for (int i = 0; i < byteMessagesArray.length; i++) {
			String hexStringToSend = convertByteArrayToHexString(byteMessagesArray[i]);
			String hexUdhToSend = convertByteArrayToHexString(byteUdhArray[i]);
			System.out.println("Part " + i + " header: " + hexUdhToSend + " and message text is: "
					+ new String(byteMessagesArray[i]));
		}
	}

	private static final char[] hexCode = "0123456789ABCDEF".toCharArray();

	public static String convertByteArrayToHexString(byte[] data) {
		StringBuilder r = new StringBuilder(data.length * 2);
		for (byte b : data) {
			r.append(hexCode[b >> 4 & 0xF]);
			r.append(hexCode[b & 0xF]);
		}
		return r.toString();
	}

	public static int getRandomNumberInRange(int min, int max) {
		if (min >= max)
			throw new IllegalArgumentException("max must be greater than min");
		Random r = new Random();
		return r.nextInt(max - min + 1) + min;
	}

}
