package com.pathpress.util

import kotlin.contracts.ExperimentalContracts
import kotlin.contracts.contract

fun Number?.toDoubleSafe(): Double = this?.toDouble() ?: 0.0

fun <T> List<T>?.orEmptyList(): List<T> = this ?: emptyList()

inline fun <K, V> Map<K, V>.getOrDefault(key: K, default: () -> V): V = this[key] ?: default()

@OptIn(ExperimentalContracts::class)
fun String?.isNotBlankSafe(): Boolean {
    contract { returns(true) implies (this@isNotBlankSafe != null) }
    return !this.isNullOrBlank()
}
