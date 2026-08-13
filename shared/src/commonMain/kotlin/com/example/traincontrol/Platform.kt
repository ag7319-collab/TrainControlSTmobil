package com.example.traincontrol

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform