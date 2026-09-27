package com.bbh.metrics

import com.cloudbees.groovy.cps.NonCPS

class MetricLine implements Serializable {

    @NonCPS
    static String tag(String value) {
        return (value ?: 'unknown').replaceAll(/[,= ]/, '\\\\$0').replace('\n', ' ')
    }

    @NonCPS
    static String text(String value) {
        return '"' + (value ?: '').replace('\\', '\\\\').replace('"', '\\"').replace('\n', ' ').take(240) + '"'
    }

    @NonCPS
    static String integer(def value) {
        return (asNumber(value).longValue() as String) + 'i'
    }

    @NonCPS
    static String number(def value) {
        return asNumber(value).doubleValue().toString()
    }

    @NonCPS
    static Number asNumber(def value) {
        if (value == null) return 0
        if (value instanceof Number) return (Number) value
        if (value instanceof Boolean) return ((Boolean) value) ? 1 : 0
        String text = value.toString().trim()
        if (text ==~ /-?\d+/) return text.toLong()
        if (text ==~ /-?\d*\.\d+([eE][-+]?\d+)?/) return text.toDouble()
        return 0
    }

    @NonCPS
    static String flag(boolean value) {
        return value ? '1i' : '0i'
    }

    @NonCPS
    static String of(String measurement, Map tags, Map fields, long epochSeconds) {
        StringBuilder sb = new StringBuilder(measurement)
        for (def entry : (tags ?: [:]).entrySet()) {
            String value = entry.value == null ? '' : entry.value.toString()
            if (!value) continue
            sb.append(',').append(entry.key).append('=').append(tag(value))
        }
        sb.append(' ')
        boolean first = true
        for (def entry : (fields ?: [:]).entrySet()) {
            if (entry.value == null) continue
            if (!first) sb.append(',')
            first = false
            sb.append(entry.key).append('=').append(entry.value)
        }
        if (first) return ''
        sb.append(' ').append(epochSeconds)
        return sb.toString()
    }
}
