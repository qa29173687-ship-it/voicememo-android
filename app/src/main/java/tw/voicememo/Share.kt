package tw.voicememo

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import java.io.File

object Share {
    const val DRIVE = "com.google.android.apps.docs"
    /** 複製到 公用 Music/錄音記事本，回傳 uri 或路徑 */
    fun export(ctx: Context, f: File): String = try {
        if (Build.VERSION.SDK_INT >= 29) {
            val cr = ctx.contentResolver
            val v = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, f.name); put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
                put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/錄音記事本"); put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val uri = cr.insert(MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), v)!!
            cr.openOutputStream(uri)!!.use { o -> f.inputStream().use { it.copyTo(o) } }
            cr.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null); uri.toString()
        } else {
            @Suppress("DEPRECATION") val d = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "錄音記事本").apply { mkdirs() }
            val out = File(d, f.name); f.copyTo(out, true); MediaScannerConnection.scanFile(ctx, arrayOf(out.path), null, null); out.path
        }
    } catch (e: Exception) { "" }

    fun ask(a: Activity, notes: List<Note>) {
        if (notes.isEmpty()) return
        val p = a.getSharedPreferences("ui", Context.MODE_PRIVATE); val checked = booleanArrayOf(p.getBoolean("txt", true))
        val audio = notes.count { it.hasAudio }
        AlertDialog.Builder(a).setTitle("上傳 ${notes.size} 筆到 Google 雲端硬碟")
            .setMultiChoiceItems(arrayOf("一併上傳文字內容 (.txt)" + if (audio < notes.size) "（純文字項目一定會附）" else ""), checked) { _, _, c -> checked[0] = c }
            .setPositiveButton("上傳") { _, _ -> p.edit().putBoolean("txt", checked[0]).apply(); upload(a, notes, checked[0]) }
            .setNegativeButton("取消", null).show()
    }

    fun upload(a: Activity, notes: List<Note>, withTxt: Boolean) {
        val uris = ArrayList<Uri>(); val auth = a.packageName + ".files"
        val share = File(a.cacheDir, "share").apply { mkdirs(); listFiles()?.forEach { it.delete() } }
        var onlyAudio = true
        notes.forEach { n ->
            val f = Store.audio(a, n)
            if (n.hasAudio && f.exists()) uris += FileProvider.getUriForFile(a, auth, f)
            if (withTxt || !n.hasAudio) {
                val name = if (n.hasAudio) f.nameWithoutExtension else Names.base(Names.at(n.id), n.caseNo)
                val t = File(share, "$name.txt"); t.writeText(Names.noteText(n)); uris += FileProvider.getUriForFile(a, auth, t); onlyAudio = false
            }
        }
        if (uris.isEmpty()) return
        val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        i.type = if (onlyAudio) "audio/mp4" else if (uris.size == 1) "text/plain" else "*/*"
        i.clipData = ClipData.newRawUri("", uris[0]).apply { uris.drop(1).forEach { addItem(ClipData.Item(it)) } }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try { a.startActivity(Intent(i).setPackage(DRIVE)) }
        catch (e: ActivityNotFoundException) { a.startActivity(Intent.createChooser(i, "上傳到…")) }
    }
}
