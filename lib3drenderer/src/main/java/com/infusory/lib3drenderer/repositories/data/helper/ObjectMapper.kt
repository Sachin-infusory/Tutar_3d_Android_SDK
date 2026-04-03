package com.infusory.lib3drenderer.repositories.data.helper


import kotlin.collections.List

interface ObjectMapper<V, R> {

    /**
     * Maps a single response object (R) to a single domain object (V).
     */
    fun doMapping(inputClass: Class<V>, responseObj: R?): V?

    /**
     * Maps a single response object (R) that contains a list of data to a Kotlin List of domain objects (V).
     */
    fun doMappingOnList(inputClass: Class<V>, responseObj: R?): List<V>?
}