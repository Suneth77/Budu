package com.budu.app.face

/**
 * The friends a parent can choose from.
 * [serverId] tells the server which personality to use.
 */
enum class Character(val displayName: String, val emoji: String, val serverId: String) {
    BUDU("Budu", "🟠", "budu"),
    DINO("Dino", "🦖", "dino"),
    ROBOT("Robo", "🤖", "robot"),
    MONKEY("Miko", "🐵", "monkey"),
    CAR("Vroom", "🚗", "car");

    companion object {
        fun from(name: String?): Character = entries.firstOrNull { it.name == name } ?: BUDU
    }
}
