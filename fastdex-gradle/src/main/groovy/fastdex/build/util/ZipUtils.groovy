package fastdex.build.util;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.util.zip.CRC32;
import java.util.zip.CheckedOutputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class ZipUtils {

    static final int BUFFER = 8192;

    public static void compress(File zipFile,File... pathName) {
        long startTime=System.currentTimeMillis();
        ZipOutputStream out = null;
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(zipFile);
            CheckedOutputStream cos = new CheckedOutputStream(fileOutputStream,
                    new CRC32());
            out = new ZipOutputStream(cos);
            String basedir = "";
            for (int i=0;i<pathName.length;i++){
                compress2(pathName[i], out, basedir);
            }
            out.close();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        long endTime=System.currentTimeMillis();
        System.out.println("压缩时间： "+(endTime-startTime)+" ms");
    }


    private static void compress2(File file, ZipOutputStream out, String basedir) throws IOException{
        /* 判断是目录还是文件 */
        if (file.isDirectory()) {
            compressDirectory(file, out, basedir);
        } else {
            compressFile(file, out, basedir);
        }
    }

    /** 压缩一个目录 */
    private static void compressDirectory(File dir, ZipOutputStream out, String basedir) throws IOException {
        if (!dir.exists())
            return;

        // 压缩文件的目录条目定义必须以"/"结尾
        ZipEntry entry = new ZipEntry(basedir + dir.getName() + "/");
        entry.setTime(dir.lastModified());
        out.putNextEntry(entry);
        out.closeEntry();

        File[] files = dir.listFiles();
        for (int i = 0; i < files.length; i++) {
            /* 递归 */
            compress2(files[i], out, basedir + dir.getName() + "/");
        }
    }

    /** 压缩一个文件 */
    private static void compressFile(File file, ZipOutputStream out, String basedir) throws IOException {
        if (!file.exists()) {
            return;
        }
        BufferedInputStream bis = new BufferedInputStream(
                new FileInputStream(file));
        ZipEntry entry = new ZipEntry(basedir + file.getName());
        out.putNextEntry(entry);
        int count;
        byte []data = new byte[BUFFER];
        while ((count = bis.read(data, 0, BUFFER)) != -1) {
            out.write(data, 0, count);
        }
        bis.close();

    }


    /**
     *
     * @Description (解压)
     * @param zipPath zip路径
     * @param outPutPath 输出路径
     */
    public static void deCompress(File zipPath, File outPutPath)  {
        long startTime=System.currentTimeMillis();
        try {
            ZipInputStream Zin=new ZipInputStream(new FileInputStream(zipPath), Charset.forName("utf-8"));//输入源zip路径
            BufferedInputStream Bin=new BufferedInputStream(Zin);
            File Fout=null;
            ZipEntry entry;
            try {
                while((entry = Zin.getNextEntry())!=null && !entry.isDirectory()){
                    Fout=new File(outPutPath,entry.getName());
                    if(!Fout.exists()){
                        (new File(Fout.getParent())).mkdirs();
                    }
                    FileOutputStream out=new FileOutputStream(Fout);
                    BufferedOutputStream Bout=new BufferedOutputStream(out);
                    int b;
                    while((b=Bin.read())!=-1){
                        Bout.write(b);
                    }
                    Bout.close();
                    out.close();
                }
                Bin.close();
                Zin.close();
            } catch (IOException e) {
                e.printStackTrace();
            }
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        long endTime=System.currentTimeMillis();
        System.out.println("解压时间： "+(endTime-startTime)+" ms");
    }

}
