package kz.rudy.ar

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform