package fastdex.build.transform

import com.android.annotations.NonNull
import com.android.annotations.Nullable
import com.android.build.api.transform.*
import com.android.build.gradle.internal.LoggerWrapper
import com.android.build.gradle.internal.pipeline.TransformManager
import com.android.build.gradle.internal.transforms.DexTransform
import com.android.build.gradle.internal.transforms.TransformInputUtil
import com.android.builder.core.DexByteCodeConverter
import com.android.builder.core.DexOptions
import com.android.builder.core.ErrorReporter
import com.android.builder.dexing.DexingType
import com.android.builder.sdk.TargetInfo
import com.android.ide.common.blame.Message
import com.android.ide.common.blame.ParsingProcessOutputHandler
import com.android.ide.common.blame.parser.DexParser
import com.android.ide.common.blame.parser.ToolOutputParser
import com.android.ide.common.process.ProcessOutputHandler
import com.android.sdklib.BuildToolInfo
import com.google.common.base.Preconditions
import com.google.common.collect.ImmutableList
import com.google.common.collect.Iterables
import com.google.common.collect.Maps
import groovy.json.JsonSlurper
import org.gradle.api.Project
import org.gradle.api.file.FileCollection

import java.lang.reflect.Field

/**
 * Created by tong on 17/11/2.
 */

public class FsdexTransform extends Transform {

    private static
    final LoggerWrapper logger = LoggerWrapper.getLogger(com.android.build.gradle.internal.transforms.DexTransform.class);

    @NonNull
    private final DexOptions dexOptions;

    @NonNull
    private final DexingType dexingType;

    private boolean preDexEnabled;

    @Nullable
    private final FileCollection mainDexListFile;

    @NonNull
    private final TargetInfo targetInfo;
    @NonNull
    private final DexByteCodeConverter dexByteCodeConverter;
    @NonNull
    private final ErrorReporter errorReporter;
    private final int minSdkVersion;

    final Project project;

    public FsdexTransform(DexTransform base,Project project) {
        Class classType = base.getClass();
        Field fieldDexOptions = classType.getDeclaredField("dexOptions");
        fieldDexOptions.setAccessible(true);
        DexOptions dexOptions= (DexOptions)fieldDexOptions.get(base)


        Field fieldDexingType = classType.getDeclaredField("dexingType");
        fieldDexingType.setAccessible(true);
        DexingType dexingType= (DexingType)fieldDexingType.get(base);

        Field fieldPreDexEnabled = classType.getDeclaredField("preDexEnabled");
        fieldPreDexEnabled.setAccessible(true);
        boolean preDexEnabled= (Boolean)fieldPreDexEnabled.get(base);

        Field fieldMainDexListFile = classType.getDeclaredField("mainDexListFile");
        fieldMainDexListFile.setAccessible(true);
        FileCollection mainDexListFile= (FileCollection)fieldMainDexListFile.get(base);


        Field fieldTargetInfo = classType.getDeclaredField("targetInfo");
        fieldTargetInfo.setAccessible(true);
        TargetInfo targetInfo= (TargetInfo)fieldTargetInfo.get(base);

        Field fieldDexByteCodeConverter = classType.getDeclaredField("dexByteCodeConverter");
        fieldDexByteCodeConverter.setAccessible(true);
        DexByteCodeConverter dexByteCodeConverter= (DexByteCodeConverter)fieldDexByteCodeConverter.get(base);

        Field fieldErrorReporter = classType.getDeclaredField("errorReporter");
        fieldErrorReporter.setAccessible(true);
        ErrorReporter errorReporter= (ErrorReporter)fieldErrorReporter.get(base);

        Field fieldMinSdkVersion = classType.getDeclaredField("minSdkVersion");
        fieldMinSdkVersion.setAccessible(true);
        int minSdkVersion= (Integer)fieldMinSdkVersion.get(base);
        this.dexOptions = dexOptions;
        this.dexingType = dexingType;
        this.preDexEnabled = preDexEnabled;
        this.mainDexListFile = mainDexListFile;
        this.targetInfo = targetInfo;
        this.dexByteCodeConverter = dexByteCodeConverter;
        this.errorReporter = errorReporter;
        this.minSdkVersion = minSdkVersion;

        this.project = project
    }

    /**
     * 读取本地普通文件，将其转化为一个字符串数组
     * @return
     */
    public ArrayList<String> getPathList(String filepath){
        try{
            String temp = null;
            File f = new File(filepath);
            String adn="";
            //指定读取编码用于读取中文
            InputStreamReader read = new InputStreamReader(new FileInputStream(f),"utf-8");
            ArrayList<String> readList = new ArrayList<String>();
            BufferedReader reader=new BufferedReader(read);
            //bufReader = new BufferedReader(new FileReader(filepath));
            while((temp=reader.readLine())!=null &&!"".equals(temp)){
                readList.add(temp);
            }
            read.close();
            return readList;
        }catch (Exception e) {
            // TODO: handle exception
            logger.info("读取文件--->失败！- 原因：文件路径错误或者文件不存在");
            e.printStackTrace();
            return null;
        }
    }

    public void writeFile(List<String> list,File file){
        try {
            FileWriter fw = new FileWriter(file, true)
            BufferedWriter bw = new BufferedWriter(fw)
            for(int i = 0;i<list.size();i++){
                String path =  list.get(i)
                bw.write(path+"\r\n")
             }
            bw.close();
            fw.close();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    public boolean containPath(String path,List<String> list){
        boolean ret=false
        if(list!=null){
            for (String pre: list){
                if(path.startsWith(pre)){
                    ret=true
                    break
                }
            }
        }
        return ret
    }



    @NonNull
    @Override
    public String getName() {
        return "dex";
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getInputTypes() {
        if (!preDexEnabled) {
            // we will take all classes and convert to DEX
            return TransformManager.CONTENT_CLASS;
        } else {
            // consume DEX files and merge them
            return TransformManager.CONTENT_DEX;
        }
    }

    @NonNull
    @Override
    public Set<QualifiedContent.ContentType> getOutputTypes() {
        return TransformManager.CONTENT_DEX;
    }

    @NonNull
    @Override
    public Set<? super QualifiedContent.Scope> getScopes() {
        return TransformManager.SCOPE_FULL_WITH_IR_FOR_DEXING;
    }

    @NonNull
    @Override
    public Collection<SecondaryFile> getSecondaryFiles() {
        if (mainDexListFile != null) {
            return ImmutableList.of(SecondaryFile.nonIncremental(mainDexListFile));
        }

        return ImmutableList.of();
    }

    @NonNull
    @Override
    public Map<String, Object> getParameterInputs() {
        try {
            // ATTENTION: if you add something here, consider adding the value to DexKey - it needs
            // to be saved if affects how dx is invoked.
            Map<String, Object> params = Maps.newHashMapWithExpectedSize(8);

            params.put("optimize", true);
            params.put("predex", preDexEnabled);
            params.put("jumbo", dexOptions.getJumboMode());
            params.put("dexing-mode", dexingType.name());
            params.put("java-max-heap-size", dexOptions.getJavaMaxHeapSize());
            params.put(
                    "additional-parameters",
                    Iterables.toString(dexOptions.getAdditionalParameters()));

            BuildToolInfo buildTools = targetInfo.getBuildTools();
            params.put("build-tools", buildTools.getRevision().toString());
            params.put("min-sdk-version", minSdkVersion);

            return params;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public boolean isIncremental() {
        return false;
    }

    @Override
    public void transform(@NonNull TransformInvocation transformInvocation)
            throws TransformException, IOException, InterruptedException {

        def slurper = new JsonSlurper()
        Map map = (Map)slurper.parse(new File(project.getRootDir(),"extra_dex.json"))
        File singleFile = mainDexListFile.getSingleFile()
        if(singleFile!=null&&singleFile.exists()&&(singleFile.getAbsolutePath().endsWith("release/maindexlist.txt")
                ||singleFile.getAbsolutePath().endsWith("release\\maindexlist.txt"))){
            List exMaindexList
            if(map.containsKey("paths")){
                exMaindexList=(List)map.get("paths")
            }
            println("change maindexlist")
            println("exMaindexList:"+exMaindexList.toString())
            ArrayList<String> list = getPathList(mainDexListFile.getSingleFile().getAbsolutePath())
            List<String> writeList = new ArrayList<>()
            for (String path :list){
                if(containPath(path,exMaindexList)){
                }else{
                    writeList.add(path)
                }
            }

            singleFile.delete()
            singleFile.createNewFile()
            writeFile(writeList,singleFile)

        }


        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        Preconditions.checkNotNull(outputProvider,
                "Missing output object for transform " + getName());

        if (!dexOptions.getKeepRuntimeAnnotatedClasses() && mainDexListFile == null) {
            logger.info("DexOptions.keepRuntimeAnnotatedClasses has no affect in native multidex.");
        }

        ProcessOutputHandler outputHandler =
                new ParsingProcessOutputHandler(
                        new ToolOutputParser(new DexParser(), Message.Kind.ERROR, logger),
                        new ToolOutputParser(new DexParser(), logger),
                        this.errorReporter);
        outputProvider.deleteAll();

        try {
            // these are either classes that should be converted directly to DEX, or DEX(s) to merge
            Collection<File> transformInputs = new ArrayList<>()
            Collection<File> extraTransformInputs = new ArrayList<>() //需要单独打出dex
            Collection<File> transformInputsTemp =
                    TransformInputUtil.getAllFiles(transformInvocation.getInputs());
            List extraDexList
            if(map.containsKey("libs")){
                extraDexList=(List)map.get("libs")
            }
            for(File file:transformInputsTemp){
                String path = file.getAbsolutePath()
                boolean contain=false
                for(String dex:extraDexList){
                    if(path.contains(dex)){
                        extraDexList.remove(dex)
                        contain=true
                        break
                    }
                }
                if(contain){
                    extraTransformInputs.add(file)
                }else{
                    transformInputs.add(file)
                }

            }

            File outputDir =
                    outputProvider.getContentLocation(
                            "main",
                            getOutputTypes(),
                            TransformManager.SCOPE_FULL_PROJECT,
                            Format.DIRECTORY);
            File extraOutputDir=new File(project.getRootDir(),"output\\dexextra"); //单独打出dex的输出目录
            // this deletes and creates the dir for the output
            com.android.utils.FileUtils.cleanOutputDir(outputDir);
            com.android.utils.FileUtils.cleanOutputDir(extraOutputDir);
            File mainDexList = null;
            if (mainDexListFile != null && dexingType == DexingType.LEGACY_MULTIDEX) {
                mainDexList = mainDexListFile.getSingleFile();
            }
            dexByteCodeConverter.convertByteCode(
                    transformInputs,
                    outputDir,
                    dexingType.isMultiDex(),
                    mainDexList,
                    dexOptions,
                    outputHandler,
                    minSdkVersion);
            if(extraTransformInputs.size()>0){
                //输出自定义的dex
                println("extraInputs:"+extraTransformInputs)
                dexByteCodeConverter.convertByteCode(
                        extraTransformInputs,
                        extraOutputDir,
                        dexingType.isMultiDex(),
                        mainDexList,
                        dexOptions,
                        outputHandler,
                        minSdkVersion);
            }

        } catch (Exception e) {
            throw new TransformException(e);
        }
    }
}
