package io.transwarp.objectstore;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.fs.Path;
import org.apache.hadoop.hyperbase.client.HyperbaseAdmin;
import org.apache.hadoop.io.IOUtils;

import java.io.*;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.transwarp.objectstore.HDFSConnector.getHDFSConf;
import static io.transwarp.objectstore.MD5Util.md5crypt;

public class UploadData {
    private Connector connector;
    private HyperbaseAdmin hyperbaseAdmin;
    private Configuration configuration;
    private Constant constant;
    private LobUtil lobUtil;

    DateFormat sdf = new SimpleDateFormat("yyyy/MM/dd HH:mm:ss");

    /**
     * 线程对象
     */
    private class Task implements Runnable {
        int num;
        CopyOnWriteArrayList<String> fileList;
        String tableName;
        LobUtil lobUtil;

        Task(int num, CopyOnWriteArrayList<String> fileList, String tableName, LobUtil lobUtil) {
            this.num = num;
            this.fileList = fileList;
            this.tableName = tableName;
            this.lobUtil = lobUtil;
        }

        @Override
        public void run() {
            try {
                while (fileList.size() != 0) {
                    String s0 = fileList.remove(0);
                    String rowkey = s0.split("\\|")[1];
                    String s = s0.split("\\|")[0];

                    File file = new File(s);
                    long fileSizeInBytes = file.length();
                    long fileSizeInKB = fileSizeInBytes / 1024;
                    if (fileSizeInKB <= 10000){
                        byte[] fileData = FileUtil.file2Byte(file);
                        lobUtil.putLob(tableName, rowkey, s, fileData);
                        System.out.println("Thread " + String.valueOf(num) + " is uploading "
                                + s + " with rowkey " + rowkey + " with " + sdf.format(new Date()));
                    } else {
                        InputStream in = new BufferedInputStream(new FileInputStream(s));
                        Path p = new Path(constant.HDFS_LARGE_FILE_DIR+"/"+rowkey);
                        FileSystem fs = p.getFileSystem(getHDFSConf());
                        OutputStream out = fs.create(p);
                        IOUtils.copyBytes(in, out, getHDFSConf());
                        fs.close();
                        IOUtils.closeStream(in);
                        lobUtil.putLob(tableName, rowkey, s, p.toString().getBytes());
                        System.out.println("Thread " + String.valueOf(num) + " is uploading "
                                + s + " with rowkey " + rowkey + " with " + sdf.format(new Date()));
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * 创建线程池，上传对象
     */
    public void go() {
        connector = new Connector();
        constant = new Constant();
        hyperbaseAdmin = connector.getHyperbaseAdmin();
        configuration = connector.getConfiguration();

        String tableName = constant.HBASE_TABLE_NAME;
        int flushSize = Integer.valueOf(constant.FLUSH_SIZE);

        lobUtil = new LobUtil(hyperbaseAdmin, configuration);
        lobUtil.createLobTable(tableName, flushSize);

        ExecutorService executorService = Executors.newFixedThreadPool(Integer.valueOf(constant.THREAD_POOL_SIZE));

        Task[] tasks = new Task[Integer.parseInt(constant.THREAD_NUM)];
        String[] folders = constant.UPLOAD_DIR.split(";");
        CopyOnWriteArrayList<String> fileList = addFiles(folders);

        for (int i = 0; i < tasks.length; ++i) {
            tasks[i] = new Task(i,fileList,tableName,lobUtil);
        }

        for (Task task : tasks) {
            executorService.execute(task);
        }
        executorService.shutdown();
        connector.close();
    }

    /**
     * 创建列表，将多个文件夹下文件以及子文件夹文件加入列表中
     * @param folders 需要上传文件的文件夹名
     * @return 文件列表
     */
    private static CopyOnWriteArrayList<String> addFiles(String[] folders) {
        CopyOnWriteArrayList<String> fileList = new CopyOnWriteArrayList<>();
        for (String folder : folders) {
            File file = new File(folder);
            File[] files = file.listFiles();
            if (files != null) {
                for (File f : files) {
                    if (!f.isDirectory()) {
                        fileList.add(f.toString()+ "|" + md5crypt(f.toString()));
                    } else {
                        recursion(f.toString(), fileList);
                    }
                }
            }
        }
        return fileList;
    }

    /**
     * 辅助函数，读取子文件夹中文件
     * @param root 文件夹名
     * @param fileList 文件列表
     */
    private static void recursion(String root, CopyOnWriteArrayList<String> fileList) {
        File file = new File(root);
        File[] subFile = file.listFiles();
        if (subFile != null) {
            for (int i = 0; i < subFile.length; i++) {
                if (subFile[i].isDirectory()) {
                    recursion(subFile[i].getAbsolutePath(), fileList);
                } else {
                    fileList.add(subFile[i].getAbsolutePath()+ "|" + md5crypt(subFile[i].getAbsolutePath()));
                }
            }
        }
    }

    // 主函数
    public static void main(String[] args) {
        UploadData uploadData = new UploadData();
        uploadData.go();
    }
}
