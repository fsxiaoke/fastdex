package fastdex.build.fs;

//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by Fernflower decompiler)
//


import com.android.build.api.transform.Format;
import com.android.build.api.transform.SecondaryFile;
import com.android.build.api.transform.Transform;
import com.android.build.api.transform.TransformException;
import com.android.build.api.transform.TransformInvocation;
import com.android.build.api.transform.TransformOutputProvider;
import com.android.build.api.transform.QualifiedContent.ContentType;
import com.android.build.api.transform.QualifiedContent.Scope;
import com.android.build.gradle.internal.LoggerWrapper;
import com.android.build.gradle.internal.pipeline.TransformManager;
import com.android.build.gradle.internal.transforms.TransformInputUtil;
import com.android.builder.core.DexByteCodeConverter;
import com.android.builder.core.DexOptions;
import com.android.builder.core.ErrorReporter;
import com.android.builder.dexing.DexingType;
import com.android.builder.sdk.TargetInfo;
import com.android.ide.common.blame.MessageReceiver;
import com.android.ide.common.blame.ParsingProcessOutputHandler;
import com.android.ide.common.blame.Message.Kind;
import com.android.ide.common.blame.parser.DexParser;
import com.android.ide.common.blame.parser.ToolOutputParser;
import com.android.ide.common.process.ProcessOutputHandler;
import com.android.sdklib.BuildToolInfo;
import com.android.utils.FileUtils;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Map;
import java.util.Set;
import org.gradle.api.file.FileCollection;

public class DexTransform extends Transform {
    private static final LoggerWrapper logger = LoggerWrapper.getLogger(
            com.android.build.gradle.internal.transforms.DexTransform.class);
    private final DexOptions dexOptions;
    private final DexingType dexingType;
    private boolean preDexEnabled;
    private final FileCollection mainDexListFile;
    private final TargetInfo targetInfo;
    private final DexByteCodeConverter dexByteCodeConverter;
    private final ErrorReporter errorReporter;
    private final int minSdkVersion;

    public DexTransform(DexOptions dexOptions, DexingType dexingType, boolean preDexEnabled, FileCollection mainDexListFile, TargetInfo targetInfo, DexByteCodeConverter dexByteCodeConverter, ErrorReporter errorReporter, int minSdkVersion) {
        this.dexOptions = dexOptions;
        this.dexingType = dexingType;
        this.preDexEnabled = preDexEnabled;
        this.mainDexListFile = mainDexListFile;
        this.targetInfo = targetInfo;
        this.dexByteCodeConverter = dexByteCodeConverter;
        this.errorReporter = errorReporter;
        this.minSdkVersion = minSdkVersion;
    }

    public String getName() {
        return "dex";
    }

    public Set<ContentType> getInputTypes() {
        return !this.preDexEnabled ? TransformManager.CONTENT_CLASS : TransformManager.CONTENT_DEX;
    }

    public Set<ContentType> getOutputTypes() {
        return TransformManager.CONTENT_DEX;
    }

    public Set<? super Scope> getScopes() {
        return TransformManager.SCOPE_FULL_WITH_IR_FOR_DEXING;
    }

    public Collection<SecondaryFile> getSecondaryFiles() {
        return this.mainDexListFile != null ? ImmutableList.of(SecondaryFile.nonIncremental(this.mainDexListFile)) : ImmutableList.of();
    }

    public Map<String, Object> getParameterInputs() {
        try {
            Map<String, Object> params = Maps.newHashMapWithExpectedSize(8);
            params.put("optimize", true);
            params.put("predex", this.preDexEnabled);
            params.put("jumbo", this.dexOptions.getJumboMode());
            params.put("dexing-mode", this.dexingType.name());
            params.put("java-max-heap-size", this.dexOptions.getJavaMaxHeapSize());
            params.put("additional-parameters", Iterables.toString(this.dexOptions.getAdditionalParameters()));
            BuildToolInfo buildTools = this.targetInfo.getBuildTools();
            params.put("build-tools", buildTools.getRevision().toString());
            params.put("min-sdk-version", this.minSdkVersion);
            return params;
        } catch (Exception var3) {
            throw new RuntimeException(var3);
        }
    }

    public boolean isIncremental() {
        return false;
    }

    public void transform(TransformInvocation transformInvocation) throws TransformException, IOException, InterruptedException {
        TransformOutputProvider outputProvider = transformInvocation.getOutputProvider();
        Preconditions.checkNotNull(outputProvider, "Missing output object for transform " + this.getName());
        if (!this.dexOptions.getKeepRuntimeAnnotatedClasses() && this.mainDexListFile == null) {
            logger.info("DexOptions.keepRuntimeAnnotatedClasses has no affect in native multidex.", new Object[0]);
        }

        ProcessOutputHandler outputHandler = new ParsingProcessOutputHandler(new ToolOutputParser(new DexParser(), Kind.ERROR, logger), new ToolOutputParser(new DexParser(), logger), new MessageReceiver[]{this.errorReporter});
        outputProvider.deleteAll();

        try {
            Collection<File> transformInputs = TransformInputUtil.getAllFiles(transformInvocation.getInputs());
            File outputDir = outputProvider.getContentLocation("main", this.getOutputTypes(), TransformManager.SCOPE_FULL_PROJECT, Format.DIRECTORY);
            FileUtils.cleanOutputDir(outputDir);
            File mainDexList = null;
            if (this.mainDexListFile != null && this.dexingType == DexingType.LEGACY_MULTIDEX) {
                mainDexList = this.mainDexListFile.getSingleFile();
            }

            this.dexByteCodeConverter.convertByteCode(transformInputs, outputDir, this.dexingType.isMultiDex(), mainDexList, this.dexOptions, outputHandler, this.minSdkVersion);
        } catch (Exception var7) {
            throw new TransformException(var7);
        }
    }
}

