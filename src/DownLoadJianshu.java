import java.io.BufferedInputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

class Configure {
    public static final String VERSION = "v1.0";
    public static final String BACKUP_DIR = System.getProperty("user.dir");
    public static final String INVALID_WINDOWS_NAMING_CHAR = "^[.\\\\/:*?\"<>|]?[\\\\/:*?\"<>|]*";

    // String line =
    // "![demo](http://upload-images.jianshu.io/upload_images/5688445-405d068bce19be10.png?imageMogr2/auto-orient/strip%7CimageView2/2/w/1240)";
    // group(1) file name; group(1) pic path
    public static final String PICTURE_LINK_PATTERN = "\\[(.*)\\]\\((.*)\\?imageMogr2.*\\)";

    public static final String LINE_SEPARATOR = System.getProperty("line.separator");
}

class Log {
    public static void d(String tag, String log) {
        System.out.println(tag + ": " + log);
    }
}

class DownloadTask implements Runnable {
    private static final String TAG = "DownloadTask";
    private File outFile;
    private String link;

    public DownloadTask(File file, String link) {
        this.outFile = file;
        this.link = link;
    }

    @Override
    public void run() {
        Log.d(TAG, "file:" + outFile.getAbsolutePath() + " link:" + link);

        BufferedInputStream bufferedIS = null;
        FileOutputStream fileOS = null;
        try {
            URL urlObj = new URL(link);
            bufferedIS = new BufferedInputStream(urlObj.openStream());
            fileOS = new FileOutputStream(outFile);

            int data = bufferedIS.read();
            while (data != -1) {
                fileOS.write(data);
                data = bufferedIS.read();
            }
        } catch (MalformedURLException e) {
            e.printStackTrace();
        } catch (IOException e) {
            e.printStackTrace();
        } finally {
            try {
                if (fileOS != null) {
                    fileOS.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
            try {
                if (bufferedIS != null) {
                    bufferedIS.close();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }
        }
    }
}

class FileBean {
    String name;
    String extension;
    boolean isRegular;

    public boolean isMarkdownFile() {
        return isRegular && "md".equals(extension);
    }
}

/**
 * DownLoadJianshu parses Markdown file to filter all uploaded pictures' link,
 * And then submits a job to download pictures.
 *
 * @author bobwang
 */

public class DownLoadJianshu {
    private ExecutorService executorService;
    private Pattern rLine;

    public DownLoadJianshu(int processNum) {
        this.executorService = Executors.newFixedThreadPool(processNum);
        this.rLine = Pattern.compile(Configure.PICTURE_LINK_PATTERN);
    }

    public boolean validateFileName(String fileName) {
        return fileName.matches("^[^.\\\\/:*?\"<>|]?[^\\\\/:*?\"<>|]*") &&
                getValidFileName(fileName).length() > 0;
    }

    public String getValidFileName(String fileName) {
        String newFileName = fileName.replaceAll(Configure.INVALID_WINDOWS_NAMING_CHAR, "");
        if (newFileName.length() == 0) {
            return null;
        }
        return newFileName;
    }

    private FileBean trimFileName(String name) {
        FileBean bean = new FileBean();
        if (name != null) {
            String[] tokens = name.split(".+?/(?=[^/]+$)");
            if (tokens.length >= 1) {
                name = tokens[tokens.length - 1];
            }
            int i = name.lastIndexOf(".");
            if (i > 0) {
                bean.isRegular = true;
                bean.extension = name.substring(i + 1);
                bean.name = name.substring(0, i);
            } else {
                bean.name = name;
            }
        }
        return bean;
    }

    // refer to https://www.mkyong.com/java/how-to-copy-directory-in-java/
    @SuppressWarnings("null")
    private void walkFolder(File src, File dest) throws IOException {
        if (src.isDirectory()) {
            // if directory not exists, create it
            if (!dest.exists()) {
                dest.mkdirs();
                Log.d("copyFolder", "Directory copied from " + src + "  to " + dest);
            }

            // list all the directory contents
            String files[] = src.list();

            for (String file : files) {
                // construct the src and dest file structure
                File srcFile = new File(src, file);
                File destFile = new File(dest, file);
                // recursive copy
                walkFolder(srcFile, destFile);
            }

        } else {
            // if file, then copy it
            // Use bytes stream to support all file types

            BufferedReader reader = null;
            BufferedWriter writer = null;
            Matcher m = null;
            try {
                FileBean fileBean = trimFileName(src.getName());
                if (fileBean.isMarkdownFile() && fileBean.name != null) {
                    File tmpDir = new File(dest.getParentFile(), fileBean.name);
                    if (!tmpDir.exists()) {
                        tmpDir.mkdirs();
                    }

                    File tmpDest = new File(tmpDir, fileBean.name + "." + fileBean.extension);
                    reader = new BufferedReader(new InputStreamReader(new FileInputStream(src), "UTF-8"));
                    writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(tmpDest), "UTF-8"));
                    String line = null;
                    int count = 0;
                    while ((line = reader.readLine()) != null) {
                        m = rLine.matcher(line);
                        if (m.find()) {

                            String link = m.group(2);
                            FileBean linkBean = trimFileName(link);
                            String name = getValidFileName(m.group(1));

                            StringBuilder stringBuilder = new StringBuilder();
                            stringBuilder.append(count++);
                            stringBuilder.append("_");
                            if (name == null) {
                                name = linkBean.name;
                            }
                            stringBuilder.append(name);
                            if (linkBean.extension == null) {
                                stringBuilder.append(".png");
                            } else {
                                stringBuilder.append(".");
                                stringBuilder.append(linkBean.extension);
                            }

                            File imageDir = new File(tmpDir, "images");
                            if (!imageDir.exists()) {
                                imageDir.mkdirs();
                            }
                            executorService.submit(new DownloadTask(new File(imageDir, stringBuilder.toString()), link));
                        }
                        writer.write(line + Configure.LINE_SEPARATOR);
                    }
                }
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            } finally {
                if (reader != null) {
                    try {
                        reader.close();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }

                if (writer != null) {
                    writer.close();
                }
            }
        }
    }

    private void waitForDone() {
        executorService.shutdown();
        while (!executorService.isTerminated()) {
        }
    }

    public static void main(String[] args) {

        System.out.println("Working Directory = " +
                System.getProperty("user.dir"));


        int processNum = Runtime.getRuntime().availableProcessors();

        Log.d("DownLoadJianshu", "Version " + Configure.VERSION);

        if (args.length < 1) {
            Log.d("Main", "Pls specify Jianshu dir");
            return;
        }

        File sF = new File(args[0]);
        if (!sF.exists()) {
            Log.d("Main", sF.getAbsolutePath() + " is not existing");
            return;
        }

        File df = new File(Configure.BACKUP_DIR, "docs");
        DownLoadJianshu loader = new DownLoadJianshu(processNum);

        try {
            loader.walkFolder(sF, df);
        } catch (IOException e) {
            Log.d("Main", "walkFolder failed --");
        }

        loader.waitForDone();
        Log.d("Main", "Finish --");
    }

}
