package com.tvlyrics.app

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build

// 沉浸式背景一次性渲染器：换歌时把（镜像封面+渐进磨砂+取色遮罩）烘焙成 Bitmap
// 之后显示静态图片 → 每帧零 shader 开销（RenderEffect 无帧间缓存，AGSL 逐帧跑会卡）
object BackgroundRenderer {

    // AGSL 渐进模糊：x1→x2 半径 0→rMax 线性渐变，7×7 变步长采样（一次性渲染，质量优先）
    private const val SKSL = """
        uniform shader content;
        uniform float x1;
        uniform float x2;
        uniform float rMax;
        half4 main(float2 coord) {
            float t = clamp((coord.x - x1) / (x2 - x1), 0.0, 1.0);
            float radius = t * rMax;
            if (radius < 1.0) return content.eval(coord);
            float step = radius / 3.0;
            half4 sum = half4(0.0);
            for (int j = -3; j <= 3; j++) {
                for (int i = -3; i <= 3; i++) {
                    sum += content.eval(coord + float2(float(i), float(j)) * step);
                }
            }
            return sum / 49.0;
        }
    """

    // 渲染完整背景（在 IO 线程执行，一次性）
    // w/h：屏幕 px；density：屏幕密度（dp→px）
    fun render(art: Bitmap, w: Int, h: Int, density: Float, baseColor: Int): Bitmap {
        val full = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(full)

        // ① 清晰内容：镜像瓦片 + 封面（全分辨率）
        drawContent(c, art, w, h)

        // ② 渐进磨砂：半分辨率渲染 + AGSL shader（HardwareRenderer 光栅化，AGSL 需 GPU）
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                val hw = w / 2
                val hh = h / 2
                val half = Bitmap.createBitmap(hw, hh, Bitmap.Config.ARGB_8888)
                drawContent(Canvas(half), art, hw, hh)

                val shader = RuntimeShader(SKSL).apply {
                    setFloatUniform("x1", hw * 0.33f)
                    setFloatUniform("x2", hw * 0.45f)
                    setFloatUniform("rMax", 50f * density / 2f)
                }
                // RenderNode + RenderEffect + HardwareRenderer 光栅化（API 33+，ImageReader 带 GPU usage）
                val node = android.graphics.RenderNode("blur").apply {
                    setPosition(0, 0, hw, hh)
                    setRenderEffect(android.graphics.RenderEffect.createRuntimeShaderEffect(shader, "content"))
                }
                val nc = node.beginRecording()
                nc.drawBitmap(half, 0f, 0f, null)
                node.endRecording()

                val reader = android.media.ImageReader.newInstance(
                    hw, hh, android.graphics.PixelFormat.RGBA_8888, 1,
                    android.hardware.HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE,
                )
                val renderer = android.graphics.HardwareRenderer().apply {
                    setContentRoot(node)
                    setSurface(reader.surface)
                }
                renderer.createRenderRequest().setWaitForPresent(true).syncAndDraw()
                val image = reader.acquireLatestImage()
                val blurred = if (image != null && image.hardwareBuffer != null) {
                    android.graphics.Bitmap.wrapHardwareBuffer(image.hardwareBuffer!!, null)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                } else null
                image?.close()
                renderer.destroy()
                reader.close()

                if (blurred != null) {
                    // 放大 2 倍画到 full，只覆盖 33% 以右（shader 已处理渐变，不透明覆盖）
                    c.save()
                    c.clipRect(w * 0.33f, 0f, w.toFloat(), h.toFloat())
                    val m = Matrix().apply { setScale(2f, 2f) }
                    c.drawBitmap(blurred, m, Paint().apply { isFilterBitmap = true })
                    c.restore()
                }
            } catch (e: Throwable) {
                android.util.Log.e("TVLyricsBG", "AGSL 渐进模糊失败，回退纯遮罩", e)
            }
        }

        // ③ 取色遮罩：35-50% 羽化渐入 α80% → 100% α100%（直接烘焙进位图）
        val a80 = (baseColor and 0x00FFFFFF) or (0xCC shl 24)
        val a100 = (baseColor and 0x00FFFFFF) or (0xFF shl 24)
        val mask = LinearGradient(
            w * 0.35f, 0f, w * 1.0f, 0f,
            intArrayOf(0, 0, a80, a100),
            floatArrayOf(0f, 0.35f, 0.50f, 1.0f),
            Shader.TileMode.CLAMP,
        )
        // LinearGradient 的 stops 是相对整个渐变轴（0.35w→1.0w），换算：
        // 屏 0.35 → (0.35-0.35)/0.65 = 0；屏 0.50 → 0.231；屏 1.0 → 1
        val maskFixed = LinearGradient(
            w * 0.35f, 0f, w.toFloat(), 0f,
            intArrayOf(0, a80, a100),
            floatArrayOf(0f, 0.2308f, 1.0f),
            Shader.TileMode.CLAMP,
        )
        c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = maskFixed })

        return full
    }

    // 清晰内容：右 55% 镜像瓦片（45% 处像素级无缝）+ 左 45% 封面
    private fun drawContent(c: Canvas, art: Bitmap, w: Int, h: Int) {
        val cw = w * 0.45f
        val ch = h.toFloat()
        // 封面 Crop 参数（与显示一致）
        val s = maxOf(cw / art.width, ch / art.height)
        val dw = art.width * s
        val dh = art.height * s
        val ox = -(dw - cw) / 2f
        val oy = -(dh - ch) / 2f

        // 右 55% 镜像瓦片（本地坐标 0 = 屏 45%）
        val right = w - cw
        c.save()
        c.clipRect(cw, 0f, w.toFloat(), h.toFloat())
        c.translate(cw, 0f)
        // 瓦片1（45-90%）：镜像
        c.save(); c.clipRect(0f, 0f, cw, ch)
        c.drawBitmap(art, Matrix().apply {
            setScale(-s, s); postTranslate(cw - ox, oy)
        }, null)
        c.restore()
        // 瓦片2（90-135%）：原样
        c.save(); c.clipRect(cw, 0f, 2f * cw, ch)
        c.drawBitmap(art, Matrix().apply {
            setScale(s, s); postTranslate(ox + cw, oy)
        }, null)
        c.restore()
        c.restore()

        // 左 45% 封面（Crop：源居中裁切到目标矩形）
        val srcW: Float; val srcH: Float
        if (dw >= cw && dh >= ch) {
            srcW = cw / s; srcH = ch / s
        } else { srcW = art.width.toFloat(); srcH = art.height.toFloat() }
        val src = android.graphics.Rect(
            ((art.width - srcW) / 2f).toInt(),
            ((art.height - srcH) / 2f).toInt(),
            ((art.width + srcW) / 2f).toInt(),
            ((art.height + srcH) / 2f).toInt(),
        )
        c.drawBitmap(art, src, RectF(0f, 0f, cw, ch), Paint().apply { isFilterBitmap = true })
    }
}
