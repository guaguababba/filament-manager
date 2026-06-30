package com.spoolsuperman.app.data.api

import retrofit2.http.*
import com.google.gson.annotations.SerializedName

data class AiVisionRequest(
    @SerializedName("model") val model: String = "qwen-vl-plus",
    @SerializedName("messages") val messages: List<AiMessage>
)

data class AiMessage(
    @SerializedName("role") val role: String = "user",
    @SerializedName("content") val content: List<AiContent>
)

data class AiContent(
    @SerializedName("type") val type: String,
    @SerializedName("text") val text: String? = null,
    @SerializedName("image_url") val imageUrl: ImageUrl? = null
)

data class ImageUrl(
    @SerializedName("url") val url: String
)

data class AiVisionResponse(
    @SerializedName("choices") val choices: List<AiChoice>? = null
)

data class AiChoice(
    @SerializedName("message") val message: AiResponseMessage? = null
)

data class AiResponseMessage(
    @SerializedName("content") val content: String? = null
)

interface AiApiService {

    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun visionInference(
        @Body request: AiVisionRequest,
        @Header("Authorization") auth: String
    ): AiVisionResponse

    @POST("chat/completions")
    @Headers("Content-Type: application/json")
    suspend fun deepseekChat(
        @Body request: Map<String, @JvmSuppressWildcards Any>,
        @Header("Authorization") auth: String
    ): Map<String, Any>
}
