package com.xgwnje.visionguard.inference.detect

/**
 * 遮罩区域，使用相对坐标 `[0,1]`。
 *
 * 与 Windows 端语义一致：推理前涂黑，涂黑结果同时进入推理、报警截图与本机预览，
 * 因此用户看到的黑色区域既不会被识别，也不会出现在通知截图里。
 * 遮罩只属于本机来源配置，不通过 `set-config` 远程同步。
 */
data class MaskRegion(val x: Float, val y: Float, val width: Float, val height: Float) {

    /** 单边最小边长，供遮罩编辑器约束。 */
    companion object {
        const val MIN_EDGE = 0.02f
        private const val SEPARATOR = ';'
        private const val FIELD_SEPARATOR = ','

        /** 校验相对坐标是否落在合法范围；非法时返回原因，合法返回 null。 */
        fun validate(region: MaskRegion): String? = when {
            region.x < 0f || region.y < 0f -> "遮罩起点不能为负"
            region.width <= 0f || region.height <= 0f -> "遮罩宽高必须大于 0"
            region.x + region.width > 1.0001f || region.y + region.height > 1.0001f -> "遮罩不能超出画面范围"
            else -> null
        }

        /** 解析 `x,y,w,h;x,y,w,h` 持久化格式；任一项非法时整串失效返回空列表。 */
        fun parseList(raw: String?): List<MaskRegion> {
            if (raw.isNullOrBlank()) return emptyList()
            val result = ArrayList<MaskRegion>()
            for (item in raw.split(SEPARATOR)) {
                if (item.isBlank()) continue
                val parts = item.split(FIELD_SEPARATOR)
                if (parts.size != 4) return emptyList()
                val values = parts.map { it.trim().toFloatOrNull() ?: return emptyList() }
                val region = MaskRegion(values[0], values[1], values[2], values[3])
                if (validate(region) != null) return emptyList()
                result.add(region)
            }
            return result
        }

        /** 格式化为 `x,y,w,h;...` 持久化串；使用紧凑十进制，避免本地化小数点差异。 */
        fun formatList(regions: List<MaskRegion>): String = regions.joinToString(SEPARATOR.toString()) { region ->
            listOf(region.x, region.y, region.width, region.height).joinToString(FIELD_SEPARATOR.toString()) { format(it) }
        }

        private fun format(value: Float): String {
            val rounded = Math.rint(value.toDouble() * 10_000.0) / 10_000.0
            return if (rounded == Math.rint(rounded)) rounded.toInt().toString() else rounded.toString()
        }
    }
}
