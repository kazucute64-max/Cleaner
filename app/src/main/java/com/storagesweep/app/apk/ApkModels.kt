package com.storagesweep.app.apk

import android.content.Context
import android.content.pm.PackageInfo
import android.os.Environment
import java.io.File

enum class ApkKind { APK, APKS, APKM, XAPK }

data class ApkEntry(val path:String,val name:String,val sizeBytes:Long,val modifiedAt:Long,val kind:ApkKind,val packageName:String?,val versionName:String?,val versionCode:Long?,val installed:Boolean,val installedVersionCode:Long?) {
    val isOldInstalledVersion get() = installed && versionCode != null && installedVersionCode != null && versionCode!! < installedVersionCode!!
    val isForUninstalledApp get() = packageName != null && !installed
}

object ApkRepository {
    private val extensions = mapOf("apk" to ApkKind.APK,"apks" to ApkKind.APKS,"apkm" to ApkKind.APKM,"xapk" to ApkKind.XAPK)
    fun scan(context: Context): List<ApkEntry> {
        val storage=Environment.getExternalStorageDirectory(); val roots=linkedSetOf(File(storage,Environment.DIRECTORY_DOWNLOADS),File(storage,"Documents"),storage)
        val installed=try { context.packageManager.getInstalledPackages(0).associate { it.packageName to versionCode(it) } } catch(_:Exception){emptyMap()}
        val seen=HashSet<String>(); val result=ArrayList<ApkEntry>()
        roots.filter { it.isDirectory }.forEach { root -> walk(root,5) { f ->
            val kind=extensions[f.extension.lowercase()] ?: return@walk
            val canonical=try{f.canonicalPath}catch(_:Exception){f.absolutePath}; if(!seen.add(canonical)) return@walk
            val meta=if(kind==ApkKind.APK) readApkMeta(context,f) else null; val pkg=meta?.first; val iv=pkg?.let{installed[it]}
            result += ApkEntry(f.absolutePath,f.name,f.length(),f.lastModified(),kind,pkg,meta?.second,meta?.third,pkg!=null&&installed.containsKey(pkg),iv)
        }}
        return result.sortedWith(compareByDescending<ApkEntry>{it.sizeBytes}.thenBy{it.name.lowercase()})
    }
    private fun walk(root:File,maxDepth:Int,visitor:(File)->Unit){ val stack=ArrayDeque<Pair<File,Int>>(); val seen=HashSet<String>(); stack.add(root to 0); while(stack.isNotEmpty()){ val (d,depth)=stack.removeLast(); val c=try{d.canonicalPath}catch(_:Exception){continue}; if(!seen.add(c))continue; val children=try{d.listFiles()}catch(_:SecurityException){null}?:continue; children.forEach{if(it.isFile)visitor(it) else if(it.isDirectory&&depth<maxDepth&&!it.name.startsWith("."))stack.add(it to depth+1)}} }
    private fun versionCode(i:PackageInfo)=if(android.os.Build.VERSION.SDK_INT>=28)i.longVersionCode else @Suppress("DEPRECATION") i.versionCode.toLong()
    private fun readApkMeta(context:Context,file:File):Triple<String,String?,Long?>?=try{val pm=context.packageManager;val i=if(android.os.Build.VERSION.SDK_INT>=33)pm.getPackageArchiveInfo(file.absolutePath,android.content.pm.PackageManager.PackageInfoFlags.of(0)) else @Suppress("DEPRECATION") pm.getPackageArchiveInfo(file.absolutePath,0);i?.let{Triple(it.packageName,it.versionName,versionCode(it))}}catch(_:Exception){null}
}

object ApkFileManager {
    private val extensions=setOf("apk","apks","apkm","xapk")
    fun delete(context:Context,path:String):Boolean=try{val root=Environment.getExternalStorageDirectory().canonicalFile;val target=File(path).canonicalFile;if(!target.path.startsWith(root.path+File.separator)||target.extension.lowercase() !in extensions||!target.isFile)return false;target.delete()&&!target.exists()}catch(_:Exception){false}
}
