package com.vibes.only

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.*
import android.provider.Settings
import okhttp3.*
import org.json.*
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.*
import java.net.URI
import java.util.*
import java.util.concurrent.*
import kotlin.math.*

class SafeMath {
    private class Parser(private val input: String) {
        private var p = 0
        private fun skip() { while (p < input.length && input[p].isWhitespace()) p++ }
        private fun eat(ch: Char): Boolean { skip(); if (p < input.length && input[p] == ch) { p++; return true }; return false }
        fun parse(): Double { val v = expr(); skip(); if (p != input.length) error("unexpected token"); return v }
        private fun expr(): Double { var v = term(); while (true) { v = when { eat('+') -> v + term(); eat('-') -> v - term(); else -> return v } } }
        private fun term(): Double { var v = power(); while (true) { v = when { eat('*') -> v * power(); eat('/') -> v / power(); eat('%') -> v % power(); else -> return v } } }
        private fun power(): Double { var v = unary(); if (eat('^')) v = v.pow(power()); return v }
        private fun unary(): Double = when { eat('+') -> unary(); eat('-') -> -unary(); else -> primary() }
        private fun primary(): Double {
            skip()
            if (eat('(')) { val v = expr(); if (!eat(')')) error("missing )"); return v }
            if (p < input.length && (input[p].isLetter() || input[p] == '_')) {
                val start = p; while (p < input.length && (input[p].isLetterOrDigit() || input[p] == '_')) p++
                val name = input.substring(start, p)
                if (name == "pi") return Math.PI
                if (name == "e") return Math.E
                if (eat('(')) {
                    val arg = expr(); if (!eat(')')) error("missing )")
                    return when (name.lowercase(Locale.US)) {
                        "sqrt" -> sqrt(arg); "sin" -> sin(arg); "cos" -> cos(arg); "tan" -> tan(arg)
                        "log" -> ln(arg); "log10" -> log10(arg); "abs" -> abs(arg); "round" -> round(arg)
                        else -> error("unsupported function")
                    }
                }
                error("unknown name")
            }
            val start = p
            while (p < input.length && (input[p].isDigit() || input[p] == '.')) p++
            if (start == p) error("number expected")
            return input.substring(start, p).toDouble()
        }
    }

    fun evaluate(raw: String): String = try {
        val expr = raw.trim().replace("×", "*").replace("÷", "/")
        if (expr.length > 120) error("expression too long")
        val value = Parser(expr).parse()
        if (!value.isFinite()) error("non-finite result")
        if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()
    } catch (e: Exception) { "Calculation error: ${e.message ?: "invalid expression"}" }

    fun looks(raw: String): Boolean {
        val s = raw.trim().replace("×", "*").replace("÷", "/")
        return s.length <= 120 && s.any { it.isDigit() } && s.matches(Regex("[0-9A-Za-z_\\s+\\-*/^().%,]+"))
    }
}
