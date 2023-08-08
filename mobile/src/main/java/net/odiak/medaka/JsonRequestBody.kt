package net.odiak.medaka

import com.squareup.moshi.JsonAdapter
import okhttp3.MediaType
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

class JsonRequestBody<T>(adapter: JsonAdapter<T>, obj: T) : RequestBody() {
    private val json = adapter.toJson(obj).toByteArray()

    override fun contentType(): MediaType {
        return "application/json; charset=utf-8".toMediaType()
    }

    override fun contentLength(): Long {
        return json.size.toLong()
    }

    override fun writeTo(sink: BufferedSink) {
        sink.write(json)
    }
}