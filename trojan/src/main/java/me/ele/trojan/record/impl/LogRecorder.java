package me.ele.trojan.record.impl;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;

import java.util.List;

import me.ele.trojan.config.LogConstants;
import me.ele.trojan.config.TrojanConfig;
import me.ele.trojan.config.TrojanConstants;
import me.ele.trojan.executor.ExecutorDispatcher;
import me.ele.trojan.helper.FileHelper;
import me.ele.trojan.helper.PermissionHelper;
import me.ele.trojan.listener.PrepareUploadListener;
import me.ele.trojan.log.Logger;
import me.ele.trojan.record.ILogFormatter;
import me.ele.trojan.record.ILogRecorder;
import me.ele.trojan.record.ILogWriter;
import me.ele.trojan.utils.AppUtils;
import me.ele.trojan.utils.DateUtils;
import me.ele.trojan.utils.DeviceUtils;
import me.ele.trojan.utils.GsonUtils;

/**
 * Created by michaelzhong on 2017/11/7.
 */
public class LogRecorder implements ILogRecorder {

    private ILogFormatter logFormatter;

    /**
     * If the MmapLogWriter's initialization have problem, change to use NormalLogWriter
     */
    private ILogWriter logWriter;

    private Handler handler = new Handler(Looper.getMainLooper());

    private Context context;

    private TrojanConfig config;
    private String dirPath;
    private String cipherKey;

    public LogRecorder(final TrojanConfig config) {
        this.logFormatter = new LogFormatter();
        this.config = config;
        this.context = config.getContext();
        this.cipherKey = config.getCipherKey();

        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for init");
                    return;
                }
                tryInitLogWriter();
            }
        });
    }


    private synchronized void tryInitLogWriter() {
        if (null != logWriter) {
            return;
        }
        dirPath = FileHelper.getTempDir(context).getAbsolutePath();
        Logger.i("LogRecorder-->tryInitLogWriter,dirPath:" + dirPath);

        if (!config.isEnableBackup()) {
            try {
                MmapLogWriter mmapLogWriter = new MmapLogWriter();
                String basicInfo = logFormatter.format(LogConstants.BASIC_TAG, getBasicInfo(config), false);
                mmapLogWriter.init(context, basicInfo, dirPath, cipherKey);
                logWriter = mmapLogWriter;
            } catch (Throwable ex) {
                ex.printStackTrace();
                initNormalLogWriter();
            }
        } else {
            initNormalLogWriter();
        }

    }

    @Override
    public void refreshUser(String user) {
        config.setUserInfo(user);
        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for refreshUser");
                    return;
                }
                String basicInfo = logFormatter.format(LogConstants.BASIC_TAG, getBasicInfo(config), false);
                try {
                    tryInitLogWriter();
                    logWriter.refreshBasicInfo(basicInfo);
                    logWriter.write(basicInfo, false);
                } catch (Throwable ex) {
                    ex.printStackTrace();
                    if (!(logWriter instanceof NormalLogWriter)) {
                        initNormalLogWriter();
                        logWriter.refreshBasicInfo(basicInfo);
                        tryWriteLog(basicInfo, false);
                    }
                }

            }
        });
    }

    @Override
    public void log(final String tag, final String msg, final boolean encryptFlag) {
        if (TextUtils.isEmpty(tag) || TextUtils.isEmpty(msg)) {
            return;
        }
        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for log");
                    return;
                }
                checkInitAndRecordSync(logFormatter.format(tag, msg, encryptFlag), encryptFlag);
            }
        });
    }

    @Override
    public void log(final String tag, final List<String> msgFieldList, final boolean encryptFlag) {
        if (TextUtils.isEmpty(tag) || msgFieldList == null || msgFieldList.size() == 0) {
            return;
        }
        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                //然后要判断是否logWriter是否已经初始化(因为可能在这之前都没权限),如果还没初始化的话，需要先初始化
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for log msgFieldList");
                    return;
                }
                checkInitAndRecordSync(logFormatter.format(tag, msgFieldList, encryptFlag), encryptFlag);
            }
        });
    }

    @Override
    public void logToJson(final String tag, final Object obj, final boolean encryptFlag) {
        if (TextUtils.isEmpty(tag) || obj == null) {
            return;
        }
        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for logToJson");
                    return;
                }
                checkInitAndRecordSync(logFormatter.format(tag, GsonUtils.toJson(obj), encryptFlag), encryptFlag);
            }
        });
    }

    @Override
    public void prepareUpload(final PrepareUploadListener listener) {
        if (listener == null) {
            return;
        }
        ExecutorDispatcher.getInstance().executeRecord(new Runnable() {
            @Override
            public void run() {
                if (!PermissionHelper.hasWriteAndReadStoragePermission(context)) {
                    Logger.e("no permission for prepareUpload");

                    handler.post(new Runnable() {
                        @Override
                        public void run() {
                            listener.failToReady();
                        }
                    });
                    return;
                }
                tryInitLogWriter();
                logWriter.closeAndRenew();

                final String writeFileName = DateUtils.getDate() + (logWriter instanceof MmapLogWriter ? TrojanConstants.MMAP : "");
                // avoid to block write operation, we just rename except the writing log file, have not compress log file
                FileHelper.renameToUpAllIfNeed(config.getContext(), writeFileName, config.getLogDir());

                handler.post(new Runnable() {
                    @Override
                    public void run() {
                        listener.readyToUpload();
                    }
                });
            }
        });
    }

    private void initNormalLogWriter() {
        Logger.e("initNormalLogWriter");
        try {
            NormalLogWriter normalLogWriter = new NormalLogWriter();
            String basicInfo = logFormatter.format(LogConstants.BASIC_TAG, getBasicInfo(config), false);
            normalLogWriter.init(context, basicInfo, dirPath, cipherKey);
            logWriter = normalLogWriter;
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void tryWriteLog(String content, boolean encryptFlag) {
        try {
            logWriter.write(content, encryptFlag);
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private String getBasicInfo(TrojanConfig config) {
        StringBuilder stringBuilder = new StringBuilder();
        stringBuilder.append("Android")
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(AppUtils.getCurProcessName(context))
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(AppUtils.getVersionName(context))
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append("~")
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(config.getUserInfo())
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(config.getDeviceId())
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(DeviceUtils.getDeviceInfo())
                .append(LogConstants.INTERNAL_SEPERATOR)
                .append(DeviceUtils.isRoot() ? 1 : 0);
        return stringBuilder.toString();
    }

    /**
     * check whether logWriter is initialized or not firstly.
     * then write the content to log file.
     *
     * @param msgContent
     * @param encryptFlag
     */
    private void checkInitAndRecordSync(String msgContent, boolean encryptFlag) {
        if (TextUtils.isEmpty(msgContent)) {
            return;
        }
        tryInitLogWriter();
        try {
            logWriter.write(msgContent, encryptFlag);
        } catch (Throwable ex) {
            ex.printStackTrace();
            if (!(logWriter instanceof NormalLogWriter)) {
                initNormalLogWriter();
                tryWriteLog(msgContent, encryptFlag);
            }
        } finally {
            msgContent = null;
        }
    }

}
