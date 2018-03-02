package fastdex.build.variant

import com.android.build.api.transform.JarInput
import com.android.build.api.transform.Transform
import com.android.build.api.transform.TransformInput
import com.android.build.api.transform.TransformInvocation
import fastdex.build.util.ClassInject
import fastdex.build.util.Constants
import fastdex.build.util.DexOperation
import fastdex.build.util.FastdexUtils
import fastdex.build.util.GradleUtils
import fastdex.common.utils.FileUtils
import fastdex.common.utils.SerializeUtils
import org.gradle.api.Project
import org.gradle.api.artifacts.Dependency
import org.gradle.api.artifacts.DependencyArtifact
import org.gradle.api.artifacts.DependencySet
import org.gradle.api.internal.artifacts.dependencies.DefaultExternalModuleDependency
import org.gradle.api.internal.artifacts.dependencies.DefaultSelfResolvingDependency

/**
 * Created by tong on 17/10/31.
 */
class FastdexBuilder {
    final FastdexVariant fastdexVariant
    final Project project
    final String variantName

    FastdexBuilder(FastdexVariant fastdexVariant) {
        this.fastdexVariant = fastdexVariant
        this.project = fastdexVariant.project
        this.variantName = fastdexVariant.variantName
    }

    /**
     * 对输入的class打桩并且保存classpath
     * @param transformInvocation
     */
    def injectInputAndSaveClassPath(TransformInvocation transformInvocation) {
        //所有输入的jar
        Set<String> jarInputFiles = new HashSet<>()

        String provideRootPath
        String div = "\\.gradle\\caches\\"
        for (TransformInput input : transformInvocation.getInputs()) {
            Collection<JarInput> jarInputs = input.getJarInputs()
            if (jarInputs != null) {
                for (JarInput jarInput : jarInputs) {
                    String filePath = jarInput.getFile().absolutePath
                    jarInputFiles.add(filePath)
                    if(provideRootPath==null&&filePath.contains(div)){
                        int index = filePath.indexOf(div)
                        provideRootPath=filePath.substring(0,index+div.length())
                    }
                }
            }
        }
        Set<Dependency> allDependencySet=fastdexVariant.getAllDependencies()
        for (Dependency dependency : allDependencySet) {
            if (dependency instanceof DefaultExternalModuleDependency) {
                File dir = new File(provideRootPath+"modules-2\\files-2.1\\"+dependency.getGroup()+"\\"+dependency.getName
                        ()+"\\"+dependency.getVersion())
                String keyword = dependency.getName()+"-"+dependency.getVersion()
                File file =searchAarOrJarFile(dir,keyword)
                if(file!=null){
                    jarInputFiles.add(file.getAbsolutePath())
                }
            } else if (dependency instanceof DefaultSelfResolvingDependency) {
                Set<File> set = ((DefaultSelfResolvingDependency) dependency).resolve()
                for(File file : set){
                    jarInputFiles.add(file.getAbsolutePath())
                }
            }
        }
        File classpathFile = new File(FastdexUtils.getBuildDir(fastdexVariant.project,fastdexVariant.variantName), Constants.CLASSPATH_FILENAME)
        SerializeUtils.serializeTo(classpathFile,jarInputFiles)
        //inject dir input
        ClassInject.injectTransformInvocation(fastdexVariant,transformInvocation)
    }


    def searchAarOrJarFile(File folder, final String keyWord) {
        File[] subFolders = folder.listFiles(new FileFilter() {
            @Override
            public boolean accept(File pathname) {

                if (pathname.isDirectory()
                        || (pathname.isFile() && pathname.getName().toLowerCase().contains(keyWord.toLowerCase())
                        &&pathname.getName().endsWith(".jar"))) {
                    return true
                }
                return false
            }
        })

        File findFile=null
        for (int i = 0; i < subFolders.length; i++) {// 循环显示文件夹或文件
            if (subFolders[i].isFile()) {
                findFile=subFolders[i]
                break
            } else {// 如果是文件夹，则递归调用本方法
                findFile = searchAarOrJarFile(subFolders[i], keyWord)
                if(findFile!=null){
                    break
                }
            }
        }

        return findFile
    }


    /**
     * 全量打包时调用默认的transform，并且做后续处理
     * @param base
     * @param transformInvocation
     * @return
     */
    def invokeNormalBuildTransform(Transform base,TransformInvocation transformInvocation) {
        //调用默认转换方法
        base.transform(transformInvocation)
        //获取dex输出路径
        File dexOutputDir = GradleUtils.getDexOutputDir(transformInvocation)

        project.logger.error("==fastdex dexOutputDir: ${dexOutputDir}")
        //缓存dex
        int dexCount = cacheNormalBuildDex(dexOutputDir)

        //复制全量打包的dex到输出路径
        hookNormalBuildDex(dexOutputDir)

        fastdexVariant.metaInfo.dexCount = dexCount
        fastdexVariant.metaInfo.buildMillis = System.currentTimeMillis()
        fastdexVariant.onDexGenerateSuccess(true,false)
    }

    /**
     * 补丁打包
     * @param patchJar
     * @param dexOutputDir
     */
    def patchBuild(Transform base,File patchJar,File dexOutputDir) {
        File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)
        DexOperation.generatePatchDex(fastdexVariant,base,patchJar,patchDex)
        fastdexVariant.metaInfo.patchDexVersion += 1
        //merged dex
        File mergedPatchDexDir = FastdexUtils.getMergedPatchDexDir(fastdexVariant.project,fastdexVariant.variantName)

        boolean willExecDexMerge = fastdexVariant.willExecDexMerge()
        boolean firstMergeDex = fastdexVariant.metaInfo.mergedDexVersion == 0

        if (willExecDexMerge) {
            //merge dex
            if (firstMergeDex) {
                //第一次执行dex merge,直接保存patchDex
                //copy 一份相同的，做冗余操作，如果直接移动文件，会丢失patch.dex造成免安装模块特别难处理
                FileUtils.copyFileUsingStream(patchDex,new File(mergedPatchDexDir,Constants.CLASSES_DEX))
            }
            else {
                //已经执行过一次dex merge
                File mergedPatchDex = new File(mergedPatchDexDir,Constants.CLASSES_DEX)
                //更新patch.dex
                DexOperation.mergeDex(fastdexVariant,mergedPatchDex,patchDex,mergedPatchDex)
            }
            fastdexVariant.metaInfo.mergedDexVersion += 1
        }
        fastdexVariant.metaInfo.save(fastdexVariant)
        //复制补丁打包的dex到输出路径，为了触发package任务
        hookPatchBuildDex(dexOutputDir,willExecDexMerge)
        fastdexVariant.onDexGenerateSuccess(false,willExecDexMerge)
    }

    /**
     * 缓存全量打包时生成的dex
     * @param dexOutputDir dex输出路径
     */
    def cacheNormalBuildDex(File dexOutputDir) {
        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)
        return FileUtils.copyDir(dexOutputDir,cacheDexDir,Constants.DEX_SUFFIX)
    }

    /**
     * 全量打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    def hookNormalBuildDex(File dexOutputDir) {
        //dexelements [fastdex-runtime.dex ${dex_cache}.listFiles]
        //runtime.dex            => classes.dex
        //dex_cache.classes.dex  => classes2.dex
        //dex_cache.classes2.dex => classes3.dex
        //dex_cache.classesN.dex => classes(N + 1).dex

        dexOutputDir = FastdexUtils.mergeDexOutputDir(dexOutputDir)

        project.logger.error(" ")
        printLogWhenDexGenerateComplete(dexOutputDir,true)

        //fastdex-runtime.dex = > classes.dex
        copyFastdexRuntimeDex(new File(dexOutputDir,Constants.CLASSES_DEX))
        printLogWhenDexGenerateComplete(dexOutputDir,true)
        project.logger.error(" ")

        //清除除了classesN.dex以外的文件
        FastdexUtils.clearDexOutputDir(dexOutputDir)
        return dexOutputDir
    }

    /**
     * 补丁打包时复制dex到指定位置
     * @param dexOutputDir dex输出路径
     */
    def hookPatchBuildDex(File dexOutputDir,boolean willExecDexMerge) {
        //dexelements [fastdex-runtime.dex patch.dex ${dex_cache}.listFiles]
        //runtime.dex            => classes.dex
        //patch.dex              => classes2.dex
        //dex_cache.classes.dex  => classes3.dex
        //dex_cache.classes2.dex => classes4.dex
        //dex_cache.classesN.dex => classes(N + 2).dex

        project.logger.error(" ")
        project.logger.error("==fastdex patch transform hook patch dex start")

        File cacheDexDir = FastdexUtils.getDexCacheDir(project,variantName)

        File patchDex = FastdexUtils.getPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)
        File mergedPatchDex = FastdexUtils.getMergedPatchDexFile(fastdexVariant.project,fastdexVariant.variantName)

        FileUtils.cleanDir(dexOutputDir)
        FileUtils.copyDir(cacheDexDir,dexOutputDir,Constants.DEX_SUFFIX)

        int dsize = 1
        //如果本次打包触发了dexmerge就不需要patch.dex了
        boolean copyPatchDex = !willExecDexMerge && FileUtils.isLegalFile(patchDex)
        if (copyPatchDex) {
            dsize += 1
        }
        boolean copyMergedPatchDex = FileUtils.isLegalFile(mergedPatchDex)
        if (copyMergedPatchDex) {
            dsize += 1
        }

        dexOutputDir = FastdexUtils.mergeDexOutputDir(dexOutputDir,dsize)

        printLogWhenDexGenerateComplete(dexOutputDir,false)
        //copy fastdex-runtime.dex
        copyFastdexRuntimeDex(new File(dexOutputDir,Constants.CLASSES_DEX))

        int point = 2
        if (copyPatchDex) {
            //copy patch.dex
            FileUtils.copyFileUsingStream(patchDex,new File(dexOutputDir,"classes${point}.dex"))
            project.logger.error("==fastdex patch.dex => " + new File(dexOutputDir,"classes${point}.dex"))

            point += 1
        }
        if (copyMergedPatchDex) {
            //copy merged-patch.dex
            FileUtils.copyFileUsingStream(mergedPatchDex,new File(dexOutputDir,"classes${point}.dex"))
            project.logger.error("==fastdex merged-patch.dex => " + new File(dexOutputDir,"classes${point}.dex"))
        }
        printLogWhenDexGenerateComplete(dexOutputDir,false)

        //清除除了classesN.dex以外的文件
        FastdexUtils.clearDexOutputDir(dexOutputDir)
        project.logger.error(" ")

        return dexOutputDir
    }

    /**
     * 把runtime.dex复制到dex输出目录
     * @param dist
     * @return
     */
    def copyFastdexRuntimeDex(File dist) {
        File buildDir = FastdexUtils.getBuildDir(project)

        File fastdexRuntimeDex = new File(buildDir, Constants.RUNTIME_DEX_FILENAME)
        if (!FileUtils.isLegalFile(fastdexRuntimeDex)) {
            FileUtils.copyResourceUsingStream(Constants.RUNTIME_DEX_FILENAME, fastdexRuntimeDex)
        }
        FileUtils.copyFileUsingStream(fastdexRuntimeDex, dist)

        project.logger.error("==fastdex fastdex-runtime.dex => " + dist)
    }

    /**
     * 当dex生成完成后打印日志
     * @param normalBuild
     */
    def printLogWhenDexGenerateComplete(File dexOutputDir,boolean normalBuild) {
        //log
        StringBuilder sb = new StringBuilder()
        File[] dexFiles = dexOutputDir.listFiles()

        if (dexFiles != null) {
            if (dexFiles.length < 7) {
                sb.append("dex-dir[")
                int idx = 0
                for (File file : dexFiles) {
                    if (file.getName().endsWith(Constants.DEX_SUFFIX)) {
                        sb.append(file.getName())
                        if (idx < (dexFiles.length - 1)) {
                            sb.append(",")
                        }
                    }
                    idx ++
                }
                sb.append("]")
            }
            else {
                int[] range = FastdexUtils.getClassesDexBoundary(dexOutputDir)
                sb.append("dex-dir[")
                if (range[0] == 1) {
                    sb.append("classes.dex")
                }
                else {
                    sb.append("classes${range[0]}.dex")
                }
                sb.append(" - ")
                sb.append("classes${range[1]}.dex")
                sb.append("]")
            }
        }

        if (normalBuild) {
            project.logger.error("==fastdex first build ${sb}")
        }
        else {
            project.logger.error("==fastdex patch build ${sb}")
        }
    }
}
