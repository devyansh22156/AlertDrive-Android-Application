package com.example.alertdrive

import android.graphics.Bitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import okhttp3.MediaType.Companion.toMediaTypeOrNull

//
//interface FlaskApi {
//    @Multipart
//    @POST("/predict")
//    suspend fun sendImage(
//        @Part image: MultipartBody.Part
//    ): retrofit2.Response<PredictionResult>
//}


object ApiClient {
    private val client = OkHttpClient()
    private const val BASE_URL = "http://192.168.213.63:5000/"

    suspend fun sendBitmapToServer(bitmap: Bitmap): PredictionResult? = withContext(Dispatchers.IO) {
        // 1) compress to JPEG
        val stream = ByteArrayOutputStream().apply {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 90, this)
        }
        val imageBytes = stream.toByteArray()

        // 2) build multipart
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "image", "frame.jpg",
                RequestBody.create("image/jpeg".toMediaTypeOrNull(), imageBytes)
            )
            .build()

        // 3) execute
        val request = Request.Builder()
            .url("${BASE_URL}predict")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) return@withContext null

            val json = JSONObject(resp.body!!.string())
            val label      = json.getString("label")
            val confidence = json.getDouble("confidence").toFloat()

            // 4) parse features array (if present)
            val featuresJson = json.optJSONArray("features")
            val features = mutableListOf<Float>()
            if (featuresJson != null) {
                for (i in 0 until featuresJson.length()) {
                    features.add(featuresJson.getDouble(i).toFloat())
                }
            }

            return@withContext PredictionResult(label, confidence, features)
        }
    }
}
