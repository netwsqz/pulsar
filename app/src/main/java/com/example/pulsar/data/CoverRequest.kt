package com.example.pulsar.data

/** 封面的显示地址；不同曲库来源自己决定如何鉴权。 */
interface CoverRequest {
    val url: String
}
