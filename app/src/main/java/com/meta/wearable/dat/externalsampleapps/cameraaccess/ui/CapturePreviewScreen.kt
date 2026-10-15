/*
 * Copyright (c) Meta Platforms, Inc. and affiliates.
 * All rights reserved.
 *
 * This source code is licensed under the license found in the
 * LICENSE file in the root directory of this source tree.
 */

// CapturePreviewScreen - Shared preview/share for captures
//
// One preview surface for both photos (Stream.capturePhoto) and recorded videos. Captures aren't
// written straight to the gallery — Share hands the capture to the system share sheet (which
// includes "Save to Photos"), and Close discards it. The recorded video temp file is deleted by
// the caller (CameraViewModel.dismissCapturePreview) when this is dismissed.

package com.meta.wearable.dat.externalsampleapps.cameraaccess.ui

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import com.meta.wearable.dat.externalsampleapps.cameraaccess.R
import com.meta.wearable.dat.externalsampleapps.cameraaccess.camera.CapturePreview
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

private const val TAG = "CapturePreviewScreen"

@Composable
fun CapturePreviewScreen(
    preview: CapturePreview,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  Dialog(
      onDismissRequest = onDismiss,
      properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Box(modifier = modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.92f))) {
      Column(
          modifier = Modifier.fillMaxSize().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.Center,
      ) {
        Box(
            modifier = Modifier.fillMaxWidth().height(460.dp).clip(RoundedCornerShape(12.dp)),
            contentAlignment = Alignment.Center,
        ) {
          when (preview) {
            is CapturePreview.Photo ->
                Image(
                    bitmap = preview.bitmap.asImageBitmap(),
                    contentDescription = stringResource(R.string.captured_photo),
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            is CapturePreview.Video -> VideoPreview(uri = preview.uri)
          }
        }

        Column(
            modifier = Modifier.padding(top = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          CircleButton(
              onClick = { shareCapture(context, preview) },
              modifier = Modifier.size(64.dp).testTag("share_button"),
          ) {
            Icon(
                imageVector = Icons.Filled.Share,
                contentDescription = stringResource(R.string.share),
                tint = Color.Black,
            )
          }
          Text(text = stringResource(R.string.share), color = Color.White)
        }
      }

      CircleButton(
          onClick = onDismiss,
          modifier =
              Modifier.align(Alignment.TopEnd)
                  .padding(20.dp)
                  .size(44.dp)
                  .testTag("close_preview_button"),
      ) {
        Icon(
            imageVector = Icons.Filled.Close,
            contentDescription = stringResource(R.string.close_preview),
            tint = Color.Black,
        )
      }
    }
  }
}

@Composable
private fun VideoPreview(uri: Uri, modifier: Modifier = Modifier) {
  AndroidView(
      modifier = modifier.fillMaxSize(),
      factory = { context ->
        VideoView(context).apply {
          val controller = MediaController(context)
          controller.setAnchorView(this)
          setMediaController(controller)
          setVideoURI(uri)
          setOnPreparedListener { start() }
        }
      },
      onRelease = { videoView -> videoView.stopPlayback() },
  )
}

private fun shareCapture(context: Context, preview: CapturePreview) {
  val uri: Uri?
  val mimeType: String
  when (preview) {
    is CapturePreview.Photo -> {
      uri = savePhotoToCache(context, preview.bitmap)
      mimeType = "image/png"
    }
    is CapturePreview.Video -> {
      uri = preview.uri
      mimeType = "video/mp4"
    }
  }
  if (uri == null) return

  val sendIntent =
      Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
      }
  val chooser =
      Intent.createChooser(sendIntent, context.getString(R.string.share)).apply {
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
      }
  context.startActivity(chooser)
}

private fun savePhotoToCache(context: Context, bitmap: Bitmap): Uri? {
  return try {
    val imagesFolder = File(context.cacheDir, "images").apply { mkdirs() }
    // Unique per share so a rapid second share can't overwrite a file a previous receiver is still
    // reading through its content URI.
    val file = File(imagesFolder, "shared_image_${SystemClock.elapsedRealtime()}.png")
    FileOutputStream(file).use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 90, output) }
    FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
  } catch (e: IOException) {
    Log.e(TAG, "Failed to write photo for sharing", e)
    null
  }
}
