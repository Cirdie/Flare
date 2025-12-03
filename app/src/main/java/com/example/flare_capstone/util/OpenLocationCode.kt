object OpenLocationCode {

    private const val CODE_ALPHABET = "23456789CFGHJMPQRVWX"
    private const val ENCODING_BASE = CODE_ALPHABET.length
    private const val PAIR_CODE_LENGTH = 10
    private const val SEPARATOR = '+'

    // Reference location for short codes (Tagum center)
    private const val TAGUM_LAT = 7.447725
    private const val TAGUM_LON = 125.804150

    fun decode(code: String): Pair<Double, Double>? {
        val cleaned = code.trim().uppercase()
        if (!cleaned.contains(SEPARATOR)) return null

        // For short codes (<=8 chars before '+'), expand to Tagum
        val plusIndex = cleaned.indexOf(SEPARATOR)
        val shortCode = cleaned.substring(0, plusIndex + 1)
        val fullCode = if (shortCode.length < 10) "$shortCode Tagum, Davao del Norte" else cleaned

        return decodeFullPlusCode(fullCode)
    }

    private fun decodeFullPlusCode(code: String): Pair<Double, Double>? {
        // Minimal decoder approximation
        try {
            // Take first 4 chars for latitude, next 4 for longitude
            val cleanCode = code.replace(" ", "")
            val latCode = cleanCode.substring(0, 4)
            val lonCode = cleanCode.substring(4, 8)

            val lat = decodeBase20(latCode) * 0.0001 - 90
            val lon = decodeBase20(lonCode) * 0.0001 - 180

            return Pair(normalizeLatitude(lat), normalizeLongitude(lon))
        } catch (_: Exception) {
            return null
        }
    }

    private fun decodeBase20(code: String): Int {
        var value = 0
        code.forEach {
            val index = CODE_ALPHABET.indexOf(it)
            if (index < 0) throw IllegalArgumentException("Invalid Plus Code character: $it")
            value = value * 20 + index
        }
        return value
    }

    private fun normalizeLongitude(lon: Double): Double {
        return ((lon + 180) % 360 + 360) % 360 - 180
    }

    private fun normalizeLatitude(lat: Double): Double {
        return lat.coerceIn(-90.0, 90.0)
    }
}
