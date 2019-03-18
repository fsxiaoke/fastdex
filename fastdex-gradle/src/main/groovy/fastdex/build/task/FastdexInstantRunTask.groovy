package fastdex.build.task

import fastdex.build.util.FastdexInstantRun
import fastdex.build.util.FastdexRuntimeException
import fastdex.build.variant.FastdexVariant
import org.gradle.api.DefaultTask
import org.gradle.api.tasks.TaskAction

/**
 * Created by tong on 17/3/12.
 */
class FastdexInstantRunTask extends DefaultTask {
    FastdexVariant fastdexVariant

    FastdexInstantRunTask() {
        group = 'fastdex'
    }

    @TaskAction
    def instantRun() {
        FastdexInstantRun fastdexInstantRun = fastdexVariant.fastdexInstantRun

        if (!fastdexInstantRun.isInstallApk()) {
            return
        }

        fastdexInstantRun.preparedDevice()
        def targetVariant = fastdexVariant.androidVariant
        project.logger.error("==fastdex normal run ${fastdexVariant.variantName}")
        //安装app
        File apkFile = targetVariant.outputs.first().getOutputFile()

        try {
            if(fastdexInstantRun.device==null){
                throw new RuntimeException("没有发现Android设备，请确认连接是否正常 adb devices")
            }
            project.logger.error("adb -s ${fastdexInstantRun.device.getSerialNumber()} install -r ${apkFile}")
            fastdexInstantRun.device.installPackage(apkFile.absolutePath,true)
            fastdexInstantRun.startBootActivity()
        } catch (Throwable e) {
            println(e.toString())
            println("Installation failed, Please perform hostsp")
        }

    }
}
