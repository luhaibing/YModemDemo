package com.bw.yml;

import android.content.Context;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;

/**
 * Util for encapsulating data package of ymodem protocol
 * <p>
 * Created by leonxtp on 2017/9/16.
 * Modified by rnd on 2019/10/28
 */

class YModemUtil {

    /*This is my concrete ymodem start signal, customise it to your needs*/
    //private static final String Data = "Data BOOTLOADER";
    private static final byte SOH = 0x01; /* Start Of Header with data size :128*/
    private static final byte STX = 0x02; /* Start Of Header with data size : 1024*/
    private static final byte EOT = 0x04; /* End Of Transmission */
    private static final byte CPMEOF = 0x1A;/* Fill the last package if not long enough */
    private static final CRC16 crc16 = new CRC16();
    //private static byte[] mInitBytes;

    /*
     * Get the first package data for hello with a terminal
     */
//    static byte[] getYModelData() {
//        return Data.getBytes();
//    }

    /**
     * Get the first package data for hello with a terminal
     * 2024
     * 3/13更新 修改成动态的 调用采用
     * String customData = "Customized Data";
     * byte[] dataBytes = getYModelData(customData);
     */
    static byte[] getYModelData(String dynamicData) {
        return dynamicData.getBytes();
    }


    /**
     * Get the file name package data
     *
     * @param fileNameString file name in String
     * @param fileByteSize   file byte size of int value
     * @param fileMd5String  the md5 of the file in String
     *
     */
    static byte[] getFileNamePackage(String fileNameString,
                                            int fileByteSize,
                                            String fileMd5String) throws IOException {

        byte seperator = 0x0;
        String fileSize = fileByteSize + "";
        byte[] byteFileSize = fileSize.getBytes();

        byte[] fileNameBytes1 = concat(fileNameString.getBytes(),
                new byte[]{seperator},
                byteFileSize);

        byte[] fileNameBytes2;
        fileNameBytes2 = Arrays.copyOf(concat(fileNameBytes1,
                new byte[]{seperator},
                fileMd5String.getBytes()), 128);

        byte seq = 0x00;
        return getDataPackage(fileNameBytes2, 128, seq);
    }

    /**
     * Get a encapsulated package data block
     *
     * @param block      byte data array
     * @param dataLength the actual content length in the block without 0 filled in it.
     * @param sequence   the package serial number
     * @return a encapsulated package data block
     */
    static byte[] getDataPackage(byte[] block, int dataLength, byte sequence) throws IOException {
        // 选择合适的包头类型：SOH (128字节) 或 STX (1024字节)
        byte headerType = (block.length == 128) ? SOH : (block.length == 1024) ? STX : SOH;
        byte[] header = getDataHeader(sequence, headerType);

        // 填充剩余数据为 CPMEOF（如果数据不足）
        if (dataLength < block.length) {
            int startFil = dataLength;
            while (startFil < block.length) {
                block[startFil] = CPMEOF;
                startFil++;
            }
        }

        // 计算CRC校验
        short crc = (short) crc16.calcCRC(block);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(baos);
        dos.writeShort(crc);
        dos.close();
        byte[] crcBytes = baos.toByteArray();

        // 返回完整的包（包头 + 数据 + CRC）
        return concat(header, block, crcBytes);
    }


    /**
     * Get the EOT package
     */
    static byte[] getEOT() {
        return new byte[]{EOT};
    }

    /**
     * Get the Last package
     */
    static byte[] getEnd() throws IOException {
        byte seq = 0x00;
        return getDataPackage(new byte[128], 128, seq);
    }

    /**
     * Get InputStream from Assets, you can customize it from the other sources
     *
     * @param fileAbsolutePath absolute path of the file in asstes
     */
    static InputStream getInputStream(Context context, String fileAbsolutePath) throws IOException {
        return new InputStreamSource().getStream(context, fileAbsolutePath);
    }

    private static byte[] getDataHeader(byte sequence, byte start) {
        //The serial number of the package increases Cyclically up to 256
        byte modSequence = (byte) (sequence % 0x256);
        byte complementSeq = (byte) ~modSequence;

        return concat(new byte[]{start},
                new byte[]{modSequence},
                new byte[]{complementSeq});
    }

    private static byte[] concat(byte[] a, byte[] b, byte[] c) {
        int aLen = a.length;
        int bLen = b.length;
        int cLen = c.length;
        byte[] concated = new byte[aLen + bLen + cLen];
        System.arraycopy(a, 0, concated, 0, aLen);
        System.arraycopy(b, 0, concated, aLen, bLen);
        System.arraycopy(c, 0, concated, aLen + bLen, cLen);
        return concated;
    }
}
