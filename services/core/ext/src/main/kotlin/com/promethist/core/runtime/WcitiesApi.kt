package com.promethist.core.runtime

import com.promethist.core.dialogue.BasicDialogue
import com.promethist.core.type.*
import javax.ws.rs.client.WebTarget

class WcitiesApi(dialogue: BasicDialogue) : DialogueApi(dialogue) {

    private val oauthToken = "<DUMMY_TOKEN>"
    private val target get() = target("http://dev.wcities.com/V3").queryParam("oauth_token", oauthToken)

    enum class Type { CITY, EVENT, RECORD, MOVIE, THEATRE }

    // Local API mockup. Remove to use the actual API endpoint
    private inline fun <reified T : Any> get(target: WebTarget): T {
        val path = "/test/wcities" + target.uri.path
                .replace("/V3", "")
                .replace(".php", "") + ".json"
        return load(path)
    }

    private fun inRadius(path: String, milesRadius: Int, additionalParameters: Map<String, Any>): WebTarget =
            with(dialogue) {
                val target = target.path(path)
                        .queryParam("miles", milesRadius)
                        .queryParam("lat", clientLocation.latitude)
                        .queryParam("lon", clientLocation.longitude)
                additionalParameters.forEach { (t, u) -> target.queryParam(t, u) }
                target
            }

    private fun withCustom(data: Dynamic, type: Type): DynamicMutableList {
        val list = DynamicMutableList(data)
        val custom = dialogue.context.session.attributes["wcities"][type.toString()]
        if (custom != null) {
            (custom as MemoryMutableList<Dynamic>).forEach { list.add(it.value) }
        }
        return list
    }

    fun nearCities(milesRadius: Int = 20, additionalParameters: Map<String, Any> = mapOf()): DynamicMutableList =
        withCustom(get(inRadius("/city_api/getNearCity.php", milesRadius, additionalParameters)), Type.CITY)

    fun events(milesRadius: Int = 20, additionalParameters: Map<String, Any> = mapOf()): DynamicMutableList = with(dialogue) {
        withCustom(get(inRadius("/event_api/getEvents.php", milesRadius, additionalParameters)
                .queryParam("tz", context.turn.input.zoneId)), Type.EVENT)
    }

    fun record(milesRadius: Int = 20, category: Int, additionalParameters: Map<String, Any> = mapOf()): DynamicMutableList =
            withCustom(get<Dynamic>(inRadius("/record_api/getRecords.php", milesRadius, additionalParameters)
                    .queryParam("cat", category))("records.record.details") as Dynamic, Type.RECORD)

    fun movies(milesRadius: Int = 20, additionalParameters: Map<String, Any> = mapOf()): DynamicMutableList =
            withCustom(get(inRadius("/movies_api/getMovies.php", milesRadius, additionalParameters)), Type.MOVIE)

    fun theaters(milesRadius: Int = 20, additionalParameters: Map<String, Any> = mapOf()): DynamicMutableList =
            withCustom(get(inRadius("/theater_api/getTheaters.php", milesRadius, additionalParameters)), Type.THEATRE)

    fun addMockedData(type: Type = Type.RECORD, vararg data: Dynamic) = with(dialogue) {
        val memoryList = MemoryMutableList(data.map { Memory(it) })
        context.session.attributes["wcities"].put(type.toString(), Memorable.pack(memoryList))
    }
}

val BasicDialogue.wcities get() = DialogueApi.get<WcitiesApi>(this)