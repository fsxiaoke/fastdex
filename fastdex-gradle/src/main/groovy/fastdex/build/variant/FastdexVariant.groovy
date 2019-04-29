package fastdex.build.variant

import com.android.build.api.transform.TransformInvocation
import com.android.build.gradle.api.ApplicationVariant
import com.android.build.gradle.internal.dependency.VariantDependencies
import com.android.build.gradle.tasks.PackageApplication
import com.github.typ0520.fastdex.Version
import fastdex.build.extension.FastdexExtension
import fastdex.build.task.FastdexInstantRunTask
import fastdex.build.transform.FastdexDexTransform
import fastdex.build.util.*
import fastdex.common.utils.FileUtils
import fastdex.common.utils.SerializeUtils
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.artifacts.Dependency
import org.gradle.api.internal.file.collections.DefaultConfigurableFileCollection

/**
 * Created by tong on 17/3/10.
 */
class FastdexVariant {
    final Project project
    final FastdexExtension configuration
    final ApplicationVariant androidVariant
    final String variantName
    final String manifestPath
    final File rootBuildDir
    final File buildDir
    final ProjectSnapshoot projectSnapshoot

    Set<LibDependency> libraryDependencies

    File textSymbolOutputFile
    File jarMergerOutputFolder
    File dexBuilderOutputFolder
    File dexMergerOutputFolder
    File preDexOutputFolder

    boolean hasDexCache
    boolean firstPatchBuild
    boolean initialized
    boolean needExecDexMerge
    boolean hasJarMergingTask
    boolean hasPreDexTask
    boolean compiledByCustomJavac
    boolean compiledByOriginJavac
    MetaInfo metaInfo
    FastdexDexTransform fastdexTransform
    FastdexInstantRun fastdexInstantRun
    FastdexInstantRunTask fastdexInstantRunTask

    TransformInvocation transformInvocation
    FastdexBuilder fastdexBuilder

    FastdexVariant(Project project, ApplicationVariant androidVariant) {
        this.project = project
        this.androidVariant = androidVariant

        this.configuration = project.fastdex
        this.variantName = androidVariant.name.capitalize()
        def processManifest = androidVariant.outputs.first().processManifest
        def processResources = androidVariant.outputs.first().processResources

        if (processManifest.properties['manifestOutputFile'] != null) {
            this.manifestPath = processManifest.manifestOutputFile.absolutePath
        } else if (processResources.properties['manifestFile']  != null) {
            this.manifestPath = androidVariant.outputs.first().processResources.manifestFile.absolutePath
        }

        this.rootBuildDir = FastdexUtils.getBuildDir(project)
        this.buildDir = FastdexUtils.getBuildDir(project,variantName)

        projectSnapshoot = new ProjectSnapshoot(this)

        if (configuration.dexMergeThreshold <= 1) {
            throw new FastdexRuntimeException("DexMergeThreshold must be greater than 1!!")
        }

        fastdexBuilder = new FastdexBuilder(this)
    }

    /*
    * 检查缓存是否过期，如果过期就删除
    */
    def prepareEnv() {
        if (initialized) {
            return
        }
        initialized = true
        hasDexCache = FastdexUtils.hasDexCache(project,variantName)

        println("==fastdex hasDexCache: ${hasDexCache}")
        if (hasDexCache) {
            File diffResultSetFile = FastdexUtils.getDiffResultSetFile(project,variantName)
            if (!FileUtils.isLegalFile(diffResultSetFile)) {
                firstPatchBuild = true
            }

            try {
                //检查meta-info文件是否存在(app/build/fastdex/${variantName}/fastdex-meta-info.json)
                File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
                if (!FileUtils.isLegalFile(metaInfoFile)) {
                    throw new JumpException("miss file : ${metaInfoFile}")
                }
                metaInfo = MetaInfo.load(project,variantName)
                if (metaInfo == null) {
                    throw new JumpException("parse json content fail: ${FastdexUtils.getMetaInfoFile(project,variantName)}")
                }

                if (metaInfo.fastdexVersion == null) {
                    throw new JumpException("cache already expired")
                }

                //检查当前的fastdexVersion和缓存对应的fastdexVersion是否一致
                if (metaInfo.fastdexVersion == null || !Version.FASTDEX_BUILD_VERSION.equals(metaInfo.fastdexVersion)) {
                    File dxJarFile = new File(FastdexUtils.getBuildDir(project),"fastdex-dx.jar")
                    File dxCommandFile = new File(FastdexUtils.getBuildDir(project),"fastdex-dx")
                    File fastdexRuntimeDex = new File(buildDir, Constants.RUNTIME_DEX_FILENAME)

                    FileUtils.deleteFile(dxJarFile)
                    FileUtils.deleteFile(dxCommandFile)
                    FileUtils.deleteFile(fastdexRuntimeDex)

                    throw new JumpException("cache fastdexVersion: ${metaInfo.fastdexVersion}, current fastdexVersion: ${Version.FASTDEX_BUILD_VERSION}")
                }

                //检查当前的依赖列表和全两打包时的依赖是否一致(app/build/fastdex/${variantName}/dependencies.json)
                File cachedDependListFile = FastdexUtils.getCachedDependListFile(project,variantName)
                if (!FileUtils.isLegalFile(cachedDependListFile)) {
                    throw new JumpException("miss depend list file: ${cachedDependListFile}")
                }

                //检查源码快照
                File sourceSetSnapshootFile = FastdexUtils.getSourceSetSnapshootFile(project,variantName)
                if (!FileUtils.isLegalFile(sourceSetSnapshootFile)) {
                    throw new JumpException("miss sourceSet snapshoot file: ${sourceSetSnapshootFile}")
                }

                //检查资源映射文件是否存在(app/build/fastdex/${variantName}/r/r.txt)
                File resourceMappingFile = FastdexUtils.getResourceMappingFile(project,variantName)
                if (!FileUtils.isLegalFile(resourceMappingFile)) {
                    throw new JumpException("miss resource mapping file: ${resourceMappingFile}")
                }

                //检查manifest快照
                File androidManifestStatFile = FastdexUtils.getAndroidManifestStatFile(project,variantName)
                if (!FileUtils.isLegalFile(androidManifestStatFile)) {
                    throw new JumpException("miss android manifest stat file: ${androidManifestStatFile}")
                }

                //如果执行过dex merge，检查merged-patch.dex是否存在
                if (metaInfo.mergedDexVersion > 0) {
                    File mergedPatchDex = FastdexUtils.getMergedPatchDex(project,variantName)
                    if (!FileUtils.isLegalFile(androidManifestStatFile)) {
                        throw new JumpException("miss merged dex file: ${mergedPatchDex}")
                    }
                }

                //如果开启了自定义的java编译任务，检查classpath文件
                if (configuration.useCustomCompile) {
                    File classpathFile = new File(FastdexUtils.getBuildDir(project,variantName),Constants.CLASSPATH_FILENAME)
                    if (!FileUtils.isLegalFile(classpathFile)) {
                        throw new JumpException("miss classpath file: ${classpathFile}")
                    }
                }

                //判断当前的工程目录和生成缓存时的工程目录是否一致
                String oldRootProjectPath = metaInfo.rootProjectPath
                String curRootProjectPath = project.rootProject.projectDir.absolutePath
                boolean isRootProjectDirChanged = metaInfo.isRootProjectDirChanged(curRootProjectPath)
                if (isRootProjectDirChanged) {
                    throw new JumpException("project path changed old: ${oldRootProjectPath} now: ${curRootProjectPath}")
                }

                projectSnapshoot.loadSnapshoot()
                //检查依赖是否发生变化
                if (isDependenciesChanged()) {
                    throw new JumpException("dependencies changed")
                }
            } catch (Throwable e) {
                if (!(e instanceof JumpException) && configuration.debug) {
                    e.printStackTrace()
                }
                println("==fastdex ${e.getMessage()}")
                delCache()
                hasDexCache = false
            }
        }

        projectSnapshoot.prepareEnv()
        if(fastdexInstantRun.manifestChanged){//minifest改变不走增量
            println("==fastdex manifestChanged")
            delCache()
            FastdexUtils.cleanCache(project,variantName)
            hasDexCache=false
            throw new FastdexRuntimeException("修改Manifest文件，请执行hostnew!!")
        }

        if (hasDexCache && metaInfo != null) {
            println("==fastdex discover dex cache for ${variantName.toLowerCase()}")

            try {
                project.tasks.getByName("transformDexArchiveWithExternalLibsDexMergerFor${variantName}").enabled = false
            } catch (Throwable e) {

            }
        }
        else {
            metaInfo = new MetaInfo()
            metaInfo.fastdexVersion = Version.FASTDEX_BUILD_VERSION

            metaInfo.projectPath = project.projectDir.absolutePath
            metaInfo.rootProjectPath = project.rootProject.projectDir.absolutePath
            metaInfo.variantName = variantName
            FastdexUtils.cleanCache(project,variantName)
            FileUtils.ensumeDir(buildDir)
        }



        if (hasDexCache) {
            //判断下当前有多少个源文件发生变化，如果超过了阈值将会执行dex merge操作
            needExecDexMerge = projectSnapshoot.diffResultSet.addOrModifiedPathInfos.size() >= configuration.dexMergeThreshold
        }
        fastdexInstantRun.onFastdexPrepare()

    }

    def delCache(){
        //删掉classes目录和transforms目录，是为了重新触发java编译和dex transform
        File classesDir = androidVariant.getVariantData().getScope().getJavaOutputDir()
        classesDir.deleteDir()
        File transformsDir = new File(androidVariant.getVariantData().getScope().getGlobalScope().getIntermediatesDir(), "/transforms")
        transformsDir.deleteDir()
        File apkLocationDir = GradleUtils.getApkLocation(androidVariant)
        apkLocationDir.deleteDir()

        println("==fastdex delete ${classesDir}")
        println("==fastdex delete ${transformsDir}")
        println("==fastdex delete ${apkLocationDir}")

        println("==fastdex we will remove ${variantName.toLowerCase()} cache")
    }

    boolean isDependenciesChanged,isDependenciesInited
    def isDependenciesChanged(){
        if(isDependenciesInited){
            return isDependenciesChanged
        }
        projectSnapshoot.loadSnapshoot()
        //检查依赖是否发生变化
        isDependenciesChanged = projectSnapshoot.isDependenciesChanged()
        isDependenciesInited=true

        return isDependenciesChanged
    }


    def getLibraryDependencies() {
        if (libraryDependencies == null) {
            libraryDependencies = LibDependency.resolveProjectDependency(project,androidVariant)
        }

        return libraryDependencies
    }

    def getAllDependencies(){
        VariantDependencies variantDeps = androidVariant.getVariantData().getVariantDependency()
        Set<Dependency>  allDependencySet = Collections.synchronizedSet(new HashSet<Dependency>())

        Set<Project> alreadyScanProjectSet = Collections.synchronizedSet(new HashSet<Project>())
        String applicationBuildTypeName = androidVariant.getBuildType().buildType.getName()

        LibDependency.scanAllDependency_3_0(project,applicationBuildTypeName,variantDeps.getCompileClasspath().getAllDependencies(),allDependencySet,alreadyScanProjectSet)

       return allDependencySet
    }

    /**
     * 获取原始manifest文件的package节点的值
     * @return
     */
    def getOriginPackageName() {
        return androidVariant.getVariantData().getVariantConfiguration().getOriginalApplicationId()
    }

    /**
     * 获取合并以后的manifest文件的package节点的值
     * @return
     */
    def getMergedPackageName() {
        return androidVariant.getVariantData().getVariantConfiguration().getApplicationId()
    }

    /**
     * 当dex生成以后
     * @param nornalBuild
     */
    def onDexGenerateSuccess(boolean nornalBuild,boolean dexMerge) {
        if (nornalBuild) {
            saveMetaInfo()
            copyRTxt()
        }
        else {
            if (dexMerge) {
                //移除idx.xml public.xml
                File idsXmlFile = FastdexUtils.getIdxXmlFile(project,variantName)
                File publicXmlFile = FastdexUtils.getPublicXmlFile(project,variantName)
                FileUtils.deleteFile(idsXmlFile)
                FileUtils.deleteFile(publicXmlFile)

                copyRTxt()
            }
        }
        projectSnapshoot.onDexGenerateSuccess(nornalBuild,dexMerge)
        fastdexInstantRun.onSourceChanged()
    }

    /**
     * 保存fastdex-meta-info文件
     * @return
     */
    def saveMetaInfo() {
        File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
        SerializeUtils.serializeTo(new FileOutputStream(metaInfoFile),metaInfo)
    }

    /**
     * 把fastdex-meta-info文件复制到合并以后的assets目录下
     * @return
     */
    def copyMetaInfo2Assets() {
        File metaInfoFile = FastdexUtils.getMetaInfoFile(project,variantName)
        if (FileUtils.isLegalFile(metaInfoFile)) {
            File assetsPath = androidVariant.getMergeAssets().outputDir
            File dest = new File(assetsPath,metaInfoFile.getName())

            println("==fastdex copy meta info: \nfrom: " + metaInfoFile + "\ninto: " + dest)
            FileUtils.copyFileUsingStream(metaInfoFile,dest)
        }
    }

    /**
     * 执行package任务之前
     * @return
     */
    def onPrePackage(Task task) {
        copyMetaInfo2Assets()
        addDexPath(task)
    }

    /**
     * xiongtj
     * 添加DexFolders
     * @param task
     * @return
     */
    def addDexPath(Task task){
        if(task instanceof PackageApplication){
          org.gradle.api.file.FileCollection fileCollection=  ((PackageApplication)task).getDexFolders()
            println("fileCollection.getFiles()："+fileCollection.getFiles())
            if(fileCollection.getFiles()==null||fileCollection.getFiles().size()==0){
                String flavor = androidVariant.getFlavorName()
                String buildType= androidVariant.getBuildType().getName()
                if(fileCollection instanceof DefaultConfigurableFileCollection){
                    String path = project.buildDir.getAbsolutePath()+File.separator+"intermediates"+File.separator+"transforms"+File.separator+"dex"+File.separator+flavor+File
                            .separator+buildType+File.separator+"0"
                    ((DefaultConfigurableFileCollection) fileCollection).setFrom(path)
                    println("==fastdex add DexFolders:"+path +"  fileCollection.getFiles()："+fileCollection.getFiles())
                }

            }
        }

    }

    /**
     * 保存资源映射文件
     */
    def copyRTxt() {
        FileUtils.copyFileUsingStream(textSymbolOutputFile,FastdexUtils.getResourceMappingFile(project,variantName))
    }

    /**
     * 补丁打包是否需要执行dex merge
     * @return
     */
    def willExecDexMerge() {
        return needExecDexMerge
    }
}
