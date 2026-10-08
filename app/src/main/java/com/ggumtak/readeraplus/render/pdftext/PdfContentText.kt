package com.ggumtak.readeraplus.render.pdftext

private fun kc(s: String): Int {
    var v = 0
    for (i in s.indices) v = v or (s[i].code shl (8 * i))
    return v
}

private val K_q = kc("q")
private val K_Q = kc("Q")
private val K_cm = kc("cm")
private val K_BT = kc("BT")
private val K_Tm = kc("Tm")
private val K_Td = kc("Td")
private val K_TD = kc("TD")
private val K_Tstar = kc("T*")
private val K_TL = kc("TL")
private val K_Tc = kc("Tc")
private val K_Tw = kc("Tw")
private val K_Tz = kc("Tz")
private val K_Ts = kc("Ts")
private val K_Tf = kc("Tf")
private val K_Tj = kc("Tj")
private val K_TJ = kc("TJ")
private val K_quote = kc("'")
private val K_dquote = kc("\"")
private val K_Do = kc("Do")
private val K_BI = kc("BI")

private val NUM = Any()

/**
 * Content-stream interpreter that only follows what moves or sizes text (q Q cm BT Tm Td TD T* TL Tc Tw Tz Ts Tf
 * Tj TJ ' " Do) and reports each glyph with its page-space box to [sink].
 */
internal class PdfContentText(
    private val fontFor: (PdfDict) -> PdfFont?,
    private val fallback: PdfFont,
    private val sink: PageTextBuilder,
) {
    /** Advances like a simple font, maps nothing. */
    private val mute: PdfFont = SimpleFont(FloatArray(256) { 0.5f }, arrayOfNulls(256))
    private var cA = 1.0
    private var cB = 0.0
    private var cC = 0.0
    private var cD = 1.0
    private var cE = 0.0
    private var cF = 0.0
    private var mA = 1.0
    private var mB = 0.0
    private var mC = 0.0
    private var mD = 1.0
    private var mE = 0.0
    private var mF = 0.0
    private var lA = 1.0
    private var lB = 0.0
    private var lC = 0.0
    private var lD = 1.0
    private var lE = 0.0
    private var lF = 0.0
    private var charSp = 0.0
    private var wordSp = 0.0
    private var hScale = 1.0
    private var leading = 0.0
    private var fontSize = 0.0
    private var rise = 0.0
    private var font: PdfFont? = null

    private val stackD = Array(MAX_Q) { DoubleArray(12) }
    private val stackF = arrayOfNulls<PdfFont>(MAX_Q)
    private var qDepth = 0
    private var qOverflow = 0
    private var ops = 0
    private var forms = 0
    private val activeForms = HashSet<Int>()

    /** Runs the page content [data] with [res] resources; [page] maps user space to the displayed page. */
    fun runPage(data: ByteArray, res: PdfDict?, page: DoubleArray) {
        cA = page[0]
        cB = page[1]
        cC = page[2]
        cD = page[3]
        cE = page[4]
        cF = page[5]
        run(data, res, 0)
    }

    private fun pushState() {
        if (qDepth >= MAX_Q) {
            qOverflow++
            return
        }
        val s = stackD[qDepth]
        s[0] = cA; s[1] = cB; s[2] = cC; s[3] = cD; s[4] = cE; s[5] = cF
        s[6] = charSp; s[7] = wordSp; s[8] = hScale; s[9] = leading; s[10] = fontSize; s[11] = rise
        stackF[qDepth] = font
        qDepth++
    }

    private fun popState() {
        if (qOverflow > 0) {
            qOverflow--
            return
        }
        if (qDepth == 0) return
        qDepth--
        val s = stackD[qDepth]
        cA = s[0]; cB = s[1]; cC = s[2]; cD = s[3]; cE = s[4]; cF = s[5]
        charSp = s[6]; wordSp = s[7]; hScale = s[8]; leading = s[9]; fontSize = s[10]; rise = s[11]
        font = stackF[qDepth]
    }

    private fun concat(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double) {
        val na = a * cA + b * cC
        val nb = a * cB + b * cD
        val nc = c * cA + d * cC
        val nd = c * cB + d * cD
        val ne = e * cA + f * cC + cE
        val nf = e * cB + f * cD + cF
        cA = na; cB = nb; cC = nc; cD = nd; cE = ne; cF = nf
    }

    private fun moveLine(tx: Double, ty: Double) {
        val e = tx * lA + ty * lC + lE
        val f = tx * lB + ty * lD + lF
        lE = e
        lF = f
        mA = lA; mB = lB; mC = lC; mD = lD; mE = lE; mF = lF
    }

    private fun run(data: ByteArray, res: PdfDict?, depth: Int) {
        val lx = PdfLexer(ArraySrc(data), 0, data.size, null)
        val nums = DoubleArray(MAX_OPERANDS)
        val objs = arrayOfNulls<Any>(MAX_OPERANDS)
        var n = 0
        while (true) {
            if (++ops > MAX_OPS) return
            when (lx.next()) {
                T_EOF -> return
                T_NUM -> if (n < MAX_OPERANDS) {
                    nums[n] = lx.numVal
                    objs[n++] = NUM
                }
                T_STR -> if (n < MAX_OPERANDS) objs[n++] = PdfString(lx.strVal)
                T_NAME -> if (n < MAX_OPERANDS) objs[n++] = PdfName(lx.nameVal)
                T_ARR_OPEN -> {
                    val a = lx.readArray(1, false)
                    if (n < MAX_OPERANDS) objs[n++] = a
                }
                T_DICT_OPEN -> {
                    val d = lx.readDict(1, false)
                    if (n < MAX_OPERANDS) objs[n++] = d
                }
                T_KW -> {
                    val code = lx.kwCode
                    if (code >= 0) {
                        when (code) {
                            K_q -> pushState()
                            K_Q -> popState()
                            K_cm -> if (n >= 6) concat(nums[n - 6], nums[n - 5], nums[n - 4], nums[n - 3], nums[n - 2], nums[n - 1])
                            K_BT -> {
                                mA = 1.0; mB = 0.0; mC = 0.0; mD = 1.0; mE = 0.0; mF = 0.0
                                lA = 1.0; lB = 0.0; lC = 0.0; lD = 1.0; lE = 0.0; lF = 0.0
                            }
                            K_Tm -> if (n >= 6) {
                                mA = nums[n - 6]; mB = nums[n - 5]; mC = nums[n - 4]
                                mD = nums[n - 3]; mE = nums[n - 2]; mF = nums[n - 1]
                                lA = mA; lB = mB; lC = mC; lD = mD; lE = mE; lF = mF
                            }
                            K_Td -> if (n >= 2) moveLine(nums[n - 2], nums[n - 1])
                            K_TD -> if (n >= 2) {
                                leading = -nums[n - 1]
                                moveLine(nums[n - 2], nums[n - 1])
                            }
                            K_Tstar -> moveLine(0.0, -leading)
                            K_TL -> if (n >= 1) leading = nums[n - 1]
                            K_Tc -> if (n >= 1) charSp = nums[n - 1]
                            K_Tw -> if (n >= 1) wordSp = nums[n - 1]
                            K_Tz -> if (n >= 1) hScale = nums[n - 1] / 100.0
                            K_Ts -> if (n >= 1) rise = nums[n - 1]
                            K_Tf -> if (n >= 2) {
                                val nm = objs[n - 2] as? PdfName
                                fontSize = nums[n - 1]
                                font = if (nm != null) lookupFont(res, nm.v) else fallback
                            }
                            K_Tj -> if (n >= 1) (objs[n - 1] as? PdfString)?.let { show(it.b) }
                            K_TJ -> if (n >= 1) (objs[n - 1] as? PdfArray)?.let { showArray(it) }
                            K_quote -> if (n >= 1) {
                                moveLine(0.0, -leading)
                                (objs[n - 1] as? PdfString)?.let { show(it.b) }
                            }
                            K_dquote -> if (n >= 3) {
                                wordSp = nums[n - 3]
                                charSp = nums[n - 2]
                                moveLine(0.0, -leading)
                                (objs[n - 1] as? PdfString)?.let { show(it.b) }
                            }
                            K_Do -> if (n >= 1) (objs[n - 1] as? PdfName)?.let { doForm(it.v, res, depth) }
                            K_BI -> skipInlineImage(lx, data)
                            else -> {}
                        }
                    }
                    n = 0
                }
                else -> {}
            }
        }
    }

    private fun lookupFont(res: PdfDict?, name: String): PdfFont {
        val d = res?.dict("Font")?.get(name) as? PdfDict ?: return fallback
        // A composite font that can't be read gives no text: its 2-byte codes read as Latin-1 would be garbage.
        return fontFor(d) ?: if (d.name("Subtype") == "Type0") mute else fallback
    }

    private fun showArray(a: PdfArray) {
        val f = font
        for (i in 0 until a.size) {
            val e = a.raw(i)
            if (e is PdfString) {
                show(e.b)
            } else {
                val v = numOf(e) ?: continue
                if (f != null && f.vertical) {
                    val ty = -v / 1000.0 * fontSize
                    mE += ty * mC
                    mF += ty * mD
                } else {
                    val tx = -v / 1000.0 * fontSize * hScale
                    mE += tx * mA
                    mF += tx * mB
                }
            }
        }
    }

    private fun show(b: ByteArray) {
        val f = font ?: return
        var i = 0
        val n = b.size
        val vertical = f.vertical
        val sx = fontSize * hScale
        val sy = fontSize
        while (i < n) {
            val code = f.readCode(b, i, n)
            val len = if (f.codeLen < 1) 1 else f.codeLen
            i += len
            val w0 = f.width(code).toDouble()

            val a1 = sx * mA
            val b1 = sx * mB
            val c1 = sy * mC
            val d1 = sy * mD
            val e1 = rise * mC + mE
            val f1 = rise * mD + mF
            val ta = a1 * cA + b1 * cC
            val tb = a1 * cB + b1 * cD
            val tcc = c1 * cA + d1 * cC
            val td = c1 * cB + d1 * cD
            val te = e1 * cA + f1 * cC + cE
            val tf = e1 * cB + f1 * cD + cF

            val s = f.unicode(code)
            if (s != null && s.isNotEmpty()) {
                val x0 = if (vertical) -w0 / 2 else 0.0
                val x1 = if (vertical) w0 / 2 else w0
                val y0 = if (vertical) -1.0 else f.descent.toDouble()
                val y1 = if (vertical) 0.0 else f.ascent.toDouble()
                val px0 = x0 * ta + y0 * tcc + te
                val py0 = x0 * tb + y0 * td + tf
                val px1 = x1 * ta + y0 * tcc + te
                val py1 = x1 * tb + y0 * td + tf
                val px2 = x0 * ta + y1 * tcc + te
                val py2 = x0 * tb + y1 * td + tf
                val px3 = x1 * ta + y1 * tcc + te
                val py3 = x1 * tb + y1 * td + tf
                val minX = minOf(minOf(px0, px1), minOf(px2, px3))
                val maxX = maxOf(maxOf(px0, px1), maxOf(px2, px3))
                val minY = minOf(minOf(py0, py1), minOf(py2, py3))
                val maxY = maxOf(maxOf(py0, py1), maxOf(py2, py3))
                val fs = Math.sqrt(tcc * tcc + td * td)
                sink.glyph(s, minX.toFloat(), minY.toFloat(), maxX.toFloat(), maxY.toFloat(), tf.toFloat(), fs.toFloat())
            }

            val space = if (code == 32 && len == 1) wordSp else 0.0
            if (vertical) {
                val ty = -fontSize + charSp + space
                mE += ty * mC
                mF += ty * mD
            } else {
                val tx = (w0 * fontSize + charSp + space) * hScale
                mE += tx * mA
                mF += tx * mB
            }
        }
    }

    private fun doForm(name: String, res: PdfDict?, depth: Int) {
        if (depth >= MAX_FORM_DEPTH || ++forms > MAX_FORMS) return
        val xo = res?.dict("XObject")?.get(name) as? PdfStream ?: return
        if (xo.dict.name("Subtype") != "Form") return
        if (!activeForms.add(xo.start)) return
        val sv = doubleArrayOf(
            cA, cB, cC, cD, cE, cF, charSp, wordSp, hScale, leading, fontSize, rise,
            mA, mB, mC, mD, mE, mF, lA, lB, lC, lD, lE, lF,
        )
        val svFont = font
        val q0 = qDepth
        val o0 = qOverflow
        try {
            val m = xo.dict.arr("Matrix")
            if (m != null && m.size >= 6) {
                concat(m.num(0) ?: 1.0, m.num(1) ?: 0.0, m.num(2) ?: 0.0, m.num(3) ?: 1.0, m.num(4) ?: 0.0, m.num(5) ?: 0.0)
            }
            val data = try {
                PdfFilters.decode(xo)
            } catch (_: PdfFormatException) {
                return
            }
            run(data, xo.dict.dict("Resources") ?: res, depth + 1)
        } finally {
            qDepth = q0
            qOverflow = o0
            cA = sv[0]; cB = sv[1]; cC = sv[2]; cD = sv[3]; cE = sv[4]; cF = sv[5]
            charSp = sv[6]; wordSp = sv[7]; hScale = sv[8]; leading = sv[9]; fontSize = sv[10]; rise = sv[11]
            mA = sv[12]; mB = sv[13]; mC = sv[14]; mD = sv[15]; mE = sv[16]; mF = sv[17]
            lA = sv[18]; lB = sv[19]; lC = sv[20]; lD = sv[21]; lE = sv[22]; lF = sv[23]
            font = svFont
            activeForms.remove(xo.start)
        }
    }

    /** Skips `BI <dict> ID <binary> EI`; the lexer is left just after `EI`. */
    private fun skipInlineImage(lx: PdfLexer, data: ByteArray) {
        while (true) {
            val t = lx.next()
            if (t == T_EOF) return
            if (t == T_KW && lx.kwIs("ID")) break
        }
        val n = data.size
        var p = lx.pos
        if (p < n && isWs(data[p].toInt() and 0xFF)) {
            if (data[p].toInt() == 13 && p + 1 < n && data[p + 1].toInt() == 10) p++
            p++
        }
        while (p + 1 < n) {
            if (data[p].toInt() == 69 && data[p + 1].toInt() == 73 &&
                (p == 0 || isWs(data[p - 1].toInt() and 0xFF)) &&
                (p + 2 >= n || isWs(data[p + 2].toInt() and 0xFF)) && textLike(data, p + 2)
            ) {
                lx.pos = p + 2
                return
            }
            p++
        }
        lx.pos = n
    }

    private fun textLike(data: ByteArray, from: Int): Boolean {
        val to = minOf(data.size, from + 12)
        for (i in from until to) {
            val c = data[i].toInt() and 0xFF
            if (c != 9 && c != 10 && c != 13 && (c < 32 || c > 126)) return false
        }
        return true
    }

    private companion object {
        const val MAX_Q = 64
        const val MAX_OPERANDS = 64
        const val MAX_OPS = 4_000_000
        const val MAX_FORM_DEPTH = 10
        const val MAX_FORMS = 2000
    }
}
