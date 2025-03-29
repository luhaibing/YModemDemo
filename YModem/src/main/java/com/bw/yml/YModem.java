package com.bw.yml;

import android.content.Context;

import java.io.IOException;

/**
 * ========================================================================================
 * THE YMODEM:
 * Send 0x05>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>* 发送0x05
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< C
 * SOH 00 FF "foo.c" "1064'' NUL[118] CRC CRC >>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< C
 * STX 01 FE data[n] CRC CRC>>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
 * ACK STX 02 FD data[n] CRC CRC>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<
 * ACK STX 03 FC data[n] CRC CRC>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * STX 04 FB data[n] CRC CRC>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * SOH 05 FA data[100] 1A[28] CRC CRC>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * EOT >>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< NAK
 * EOT>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< C
 * SOH 00 FF NUL[128] CRC CRC >>>>>>>>>>>>>>>>>>>>>>>
 * <<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<<< ACK
 * ===========================================================================================
 *
 * 传输协议 编辑 ArdWang
 * 于 2018/6/5 15:49完成
 *
 * version v2.0.0
 * version v2.0.1
 *
 * version v2.0.2
 *
 */

public class YModem implements FileStreamThread.DataRaderListener {

    private static final int STEP_INIT = 0x00;
    private static final int STEP_FILE_NAME = 0x01;
    private static final int STEP_FILE_NAME_ACK = 0x02;
    private static final int STEP_FILE_BODY = 0x03;
    private static final int STEP_EOT = 0x04;
    private static final int STEP_END = 0x05;
    private static final int STEP_SUCCEED = 0x06;
    private static final int STEP_FAILED = 0x07;
    private static int CURR_STEP = STEP_INIT;

    private static final byte ACK = 0x06; /* ACKnowlege */
    private static final byte NAK = 0x15; /* Negative AcKnowlege */
    private static final byte CAN = 0x18; /* CANcel character */
    private static final byte ST_C = 'C';
    private static final String MD5_OK = "MD5_OK";
    private static final String MD5_ERR = "MD5_ERR";

    private final Context mContext;
    private final String filePath;
    private final String fileNameString;
    private final String fileMd5String;
    private final YModemListener listener;

    private final TimeOutHelper timerHelper = new TimeOutHelper();
    private FileStreamThread streamThread;

    //bytes has been sent of this transmission
    private int bytesSent = 0;
    //package data of current sending, used for int case of fail
    private byte[] currSending = null;
    private int packageErrorTimes = 0;
    protected static Integer mSize = 1024;
    private final int maxRetries;
    //the timeout interval for a single package
    private final int packageTimeOut;
    private final long interval;

    /**
     * Construct of the YModemBLE,you may don't need the fileMD5 checking,remove it
     * YMODESMLE的构造，您可能不需要FLIMD5检查，删除它
     *
     * @param filePath       absolute path of the file
     * @param fileNameString file name for sending to the terminal
     * @param fileMd5String  md5 for terminal checking after transmission finished 传输结束后的终端检查MD5
     */
    private YModem(Context context, String filePath,
                   String fileNameString, String fileMd5String, Integer size,
                   int maxRetries, int interval, int packageTimeOut,
                   YModemListener listener) {
        this.filePath = filePath;
        this.fileNameString = fileNameString;
        this.fileMd5String = fileMd5String;
        if(size==0) {
            size = 1024;
        }
        mSize = size;
        this.mContext = context;
        this.listener = listener;
        this.interval = Math.max(Math.min(interval, 1000 * 10), 25);
        this.maxRetries = Math.max(maxRetries, 6);
        this.packageTimeOut = Math.max(packageTimeOut, 0);
    }

    /**
     * Start the transmission
     */
    public void start(String data) {
        bytesSent = 0;
        currSending = null;
        CURR_STEP = STEP_INIT;
        packageErrorTimes = 0;
        if (streamThread != null) {
            streamThread.release();
            streamThread = null;
        }
        timerHelper.stopTimer();
        sendData(data);
    }

    /**
     * Stop the transmission when you don't need it or shut it down in an accident
     * 停止传输当你不需要它或关闭它在一次事故
     * 内部停止
     */
    private void internalShutdown() {
        bytesSent = 0;
        currSending = null;
        packageErrorTimes = 0;
        if (streamThread != null) {
            streamThread.release();
            streamThread = null;
        }
        timerHelper.stopTimer();
        timerHelper.unRegisterListener();
    }

    /**
     * 外部停止
     */
    public void stop() {
        internalShutdown();
        if (getCurrStep() < STEP_SUCCEED) {
            if (listener != null) {
                listener.onFailed("Stopped.");
            }
            CURR_STEP = STEP_FAILED;
        }
    }

    /**
     * Method for the outer caller when received data from the terminal
     * 接收来自终端的数据时外部呼叫者的方法
     */
    public void onReceiveData(byte[] respData) {
        //Stop the package timer
        timerHelper.stopTimer();
        int currStep = getCurrStep();
        if (currStep >= STEP_SUCCEED) {
            Lg.f("YModem is Done.");
            return;
        }
        if (respData != null && respData.length > 0) {
            Lg.f("YModem received " + respData.length + " bytes.");
            switch (currStep) {
                case STEP_INIT:
                    handleData(respData);
                    break;
                case STEP_FILE_NAME:
                case STEP_FILE_NAME_ACK:
                    handleFileName(respData);
                    break;
                case STEP_FILE_BODY:
                    handleFileBody(respData);
                    break;
                case STEP_EOT:
                    handleEOT(respData);
                    break;
                case STEP_END:
                    handleEnd(respData);
                    break;
                default:
                    break;
            }
        } else {
            Lg.f("The terminal do responsed something, but received nothing??");
        }
    }

    /**
     * ==============================================================================
     * Methods for sending data begin
     *
     * 此方法更改如果没有第一包标注位就不需要发送数据
     * =》直接发送FileName
     *
     * ==============================================================================
     */
    private void sendData(String data) {
        streamThread = new FileStreamThread(mContext, filePath, this, interval);
        if(data != null) {
            CURR_STEP = STEP_INIT;
            Lg.f("StartData!!!");
            byte[] hello = YModemUtil.getYModelData(data);
            sendPackageData(hello);
        }else{
            packageErrorTimes = 0;
            sendFileName();
        }
    }

    private void sendFileName() {
        CURR_STEP = STEP_FILE_NAME;
        Lg.f("sendFileName");
        try {
            int fileByteSize = streamThread.getFileByteSize();
            byte[] fileNamePackage = YModemUtil.getFileNamePackage(fileNameString, fileByteSize
                    , fileMd5String);
            sendPackageData(fileNamePackage);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private void startSendFileData() {
        CURR_STEP = STEP_FILE_BODY;
        Lg.f("startSendFileData");
        streamThread.start();
    }

    //Callback from the data reading thread when a data package is ready

    private int currentDataLength;

    @Override
    public void onDataReady(int length, byte[] data) {
        currentDataLength = length;
        sendPackageData(data);
    }

    private void sendEOT() {
        CURR_STEP = STEP_EOT;
        Lg.f("sendEOT");
        if (listener != null) {
            listener.onDataReady(YModemUtil.getEOT());
        }
    }

    private void sendEND() {
        CURR_STEP = STEP_END;
        Lg.f("sendEND");
        if (listener != null) {
            try {
                listener.onDataReady(YModemUtil.getEnd());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }

    private void sendPackageData(byte[] packageData) {
        if (listener != null && packageData != null) {
            currSending = packageData;
            //Start the timer, it will be cancelled when reponse received,
            // or trigger the timeout and resend the current package data
            //启动计时器，当收到回复时将被取消，
            //或触发超时并重新发送当前包数据
            reClock();
            listener.onDataReady(packageData);
        }
    }

    /**
     * 重新倒计时
     */
    private void reClock() {
        if (packageTimeOut > 0) {
            Lg.f("start the countdown( " + packageTimeOut + " ).");
            timerHelper.startTimer(timeoutListener, packageTimeOut);
        } else {
            // 开始倒计时
            Lg.f("no countdown( " + packageTimeOut + " ).");
        }
    }

    /**
     * ==============================================================================
     * Method for handling the response of a package
     * ==============================================================================
     */
    private void handleData(byte[] value) {
        int character = value[0];
        if (character == ST_C) {//Receive "C" for "HELLO"
            Lg.f("Received 'C'");
            packageErrorTimes = 0;
            sendFileName();
        } else {
            handleOthers(character);
        }
    }

    //The file name package was responsed
    private void handleFileName(byte[] value) {
        if (value.length == 2 && value[0] == ACK && value[1] == ST_C) {//Receive 'ACK C' for file name
            Lg.f("Received 'ACK C'");
            packageErrorTimes = 0;
            startSendFileData();
        } else if (getCurrStep() == STEP_FILE_NAME && value.length == 1 && value[0] == ACK) {
            Lg.f("Received 'ACK'");
            CURR_STEP = STEP_FILE_NAME_ACK;
            packageErrorTimes = 0;
            reClock();
        } else if (getCurrStep() == STEP_FILE_NAME_ACK && value[0] == ST_C) {
            Lg.f("Received 'C'");
            packageErrorTimes = 0;
            startSendFileData();
        } else if (value[0] == ST_C) {//Receive 'C' for file name, this package should be resent
            Lg.f("Received 'C'");
            handlePackageFail("Received 'C' without 'ACK' after sent file name");
        } else {
            handleOthers(value[0]);
        }
    }

    private void handleFileBody(byte[] value) {
        if (value.length == 1 && value[0] == ACK) {//Receive ACK for file data
            Lg.f("Received 'ACK'");
            packageErrorTimes = 0;
            bytesSent += currentDataLength;
            try {
                if (listener != null) {
                    listener.onProgress(bytesSent, streamThread.getFileByteSize());
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
            streamThread.keepReading();

        } else if (value.length == 1 && value[0] == ST_C) {
            Lg.f("Received 'C'");
            //Receive C for file data, the ymodem cannot handle this circumstance, transmission failed...
            handlePackageFail("Received 'C' after sent file data");
        } else {
            handleOthers(value[0]);
        }
    }

    private void handleEOT(byte[] value) {
        if (value[0] == ACK) {
            Lg.f("Received 'ACK'");
            packageErrorTimes = 0;
            sendEND();
        } else if (value[0] == ST_C) {//As we haven't received ACK, we should resend EOT
            handlePackageFail("Received 'C' after sent EOT");
        } else if(value[0]==NAK){ //如果是NAK的话 再次发送一次EOT数据
            sendEOT();
        }else{
            handleOthers(value[0]);
        }
    }

    private void handleEnd(byte[] character) {
        if (character[0] == ACK) {//The last ACK represents that the transmission has been finished, but we should validate the file
            Lg.f("Received 'ACK'");
            //发送已经成功，完全结束
            internalShutdown();
            if (listener != null) {
                listener.onSuccess();
            }
            CURR_STEP = STEP_SUCCEED;
        } else if ((new String(character)).equals(MD5_OK)) {//The file data has been checked,Well Done!
            Lg.f("Received 'MD5_OK'");
            internalShutdown();
            if (listener != null) {
                listener.onSuccess();
            }
            CURR_STEP = STEP_SUCCEED;
        } else if ((new String(character)).equals(MD5_ERR)) {//Oops...Transmission Failed...
            Lg.f("Received 'MD5_ERR'");
            internalShutdown();
            if (listener != null) {
                listener.onFailed("MD5 check failed!!!");
            }
            CURR_STEP = STEP_FAILED;
        } else {
            handleOthers(character[0]);
        }
    }

    private void handleOthers(int character) {
        if (character == NAK) {//We need to resend this package as the terminal failed when checking the crc
            Lg.f("Received 'NAK'");
            handlePackageFail("Received NAK");
        } else if (character == CAN) {//Some big problem occurred, transmission failed...
            Lg.f("Received 'CAN'");
            internalShutdown();
            if (listener != null) {
                listener.onFailed("Received CAN");
            }
            CURR_STEP = STEP_FAILED;
        }
    }

    //Handle a failed package data ,resend it up to MAX_PACKAGE_SEND_ERROR_TIMES times.
    //处理失败的包数据
    //If still failed, then the transmission failed.
    private void handlePackageFail(String reason) {
        packageErrorTimes++;
        Lg.f("Fail:" + reason + " for " + packageErrorTimes + " times");
        if (packageErrorTimes < maxRetries) {
            sendPackageData(currSending);
        } else {
            //Still, we stop the transmission, release the resources
            internalShutdown();
            if (listener != null) {
                listener.onFailed(reason);
            }
            CURR_STEP = STEP_FAILED;
        }
    }

    /* The InputStream data reading thread was done */
    @Override
    public void onFinish() {
        sendEOT();
    }

    //The timeout listener
    private final TimeOutHelper.ITimeOut timeoutListener = new TimeOutHelper.ITimeOut() {
        @Override
        public void onTimeOut() {
            Lg.f("------ time out ------");
            if (currSending != null) {
                handlePackageFail("package timeout...");
            }
        }
    };

    public static class Builder {
        private Context context;
        private String filePath;
        private String fileNameString;
        private String fileMd5String;
        private Integer size;
        private YModemListener listener;

        // 重试次数据
        private int maxRetries;

        // 发送FileBody 时每帧数据发送线程睡眠时长; (10ms->10s)
        private int interval;

        // 每包数据超时时长 packageTimeOut
        private int packageTimeOut;

        public Builder with(Context context, int maxRetries, int interval, int packageTimeOut) {
            this.context = context;
            this.maxRetries = maxRetries;
            this.interval = interval;
            this.packageTimeOut = packageTimeOut;
            return this;
        }

        public Builder filePath(String filePath) {
            this.filePath = filePath;
            return this;
        }

        public Builder fileName(String fileName) {
            this.fileNameString = fileName;
            return this;
        }

        public Builder sendSize(Integer size){
            this.size = size;
            return this;
        }

        public Builder checkMd5(String fileMd5String) {
            this.fileMd5String = fileMd5String;
            return this;
        }

        public Builder callback(YModemListener listener) {
            this.listener = listener;
            return this;
        }

        public YModem build() {
            return new YModem(context, filePath, fileNameString, fileMd5String, size, maxRetries, interval, packageTimeOut, listener);
        }

    }

    /**
     * 获取当前步骤数
     *
     * @return 当前步骤数
     */
    public int getCurrStep() {
        return CURR_STEP;
    }

    /**
     * 查询当前是否在运行中
     *
     * @return 当前是否在运行中
     */
    public boolean isRunning() {
        int currStep = getCurrStep();
        return currStep > STEP_INIT && currStep < STEP_SUCCEED;
    }

    /**
     * 查询当前是否已结束
     *
     * @return 当前是否已结束
     */
    public boolean isComplete() {
        int currStep = getCurrStep();
        return currStep >= STEP_SUCCEED;
    }

}
