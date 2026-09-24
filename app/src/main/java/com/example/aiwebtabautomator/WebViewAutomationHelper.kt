package com.example.aiwebtabautomator

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebView
import org.json.JSONTokener
import org.json.JSONObject

/**
 * WebView-only automation helper. It operates on the app's own visible WebView and does not
 * request Accessibility privileges or attempt to bypass CAPTCHAs, login checks, or bot controls.
 */
class WebViewAutomationHelper {
    companion object {
        private const val FIXED_TYPING_INTERVAL_MS = 35L
        private const val CLICK_AFTER_TYPING_PADDING_MS = 250L
        private const val RESULT_POLL_MS = 1_000L
        private const val RESULT_TIMEOUT_MS = 45_000L
    }

    private val mainHandler = Handler(Looper.getMainLooper())

    fun extractLastAssistantText(webView: WebView, callback: (String) -> Unit) {
        val script = """
            (function() {
              const selectors = [
                '[data-message-author-role="assistant"]',
                '[data-testid*="assistant"]',
                '[class*="assistant"]',
                '[role="article"]',
                'article'
              ];
              let nodes = [];
              for (const selector of selectors) {
                nodes = Array.from(document.querySelectorAll(selector))
                  .filter(n => (n.innerText || '').trim().length > 0);
                if (nodes.length > 0) break;
              }
              return nodes.length ? (nodes[nodes.length - 1].innerText || '').trim() : '';
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw -> callback(decodeJavascriptString(raw)) }
    }

    fun sendMessage(
        webView: WebView,
        mapping: CoordinateMapping,
        message: String,
        callback: (success: Boolean, error: String?) -> Unit
    ) {
        if (webView.width <= 0 || webView.height <= 0) {
            callback(false, "WebView has no visible size")
            return
        }
        if (!mappingValuesAreValid(mapping)) {
            callback(false, "Saved coordinates are outside the current WebView")
            return
        }

        // elementFromPoint uses CSS pixels, not Android physical pixels. Resolve the current
        // CSS viewport in JavaScript so density and browser zoom do not shift the click.
        val textJson = JSONObject.quote(message)
        val script = """
            (function() {
              const vw = document.documentElement.clientWidth || window.innerWidth;
              const vh = document.documentElement.clientHeight || window.innerHeight;
              const x = Math.round(${mapping.inputX} * vw);
              const y = Math.round(${mapping.inputY} * vh);
              const text = $textJson;
              const hit = document.elementFromPoint(x, y);
              if (!hit) return JSON.stringify({ok:false,error:'No element at input coordinate'});
              const target = hit.closest('textarea,input,[contenteditable="true"]') || hit;
              target.focus();
              let i = 0;
              const writeOne = () => {
                if (i >= text.length) {
                  target.dispatchEvent(new Event('change', {bubbles:true}));
                  return;
                }
                const c = text[i++];
                if (target.isContentEditable) {
                  document.execCommand('insertText', false, c);
                } else {
                  const proto = target instanceof HTMLTextAreaElement
                    ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
                  const setter = Object.getOwnPropertyDescriptor(proto, 'value')?.set;
                  if (setter) setter.call(target, target.value + c); else target.value += c;
                  target.dispatchEvent(new InputEvent('input', {
                    bubbles:true, inputType:'insertText', data:c
                  }));
                }
                setTimeout(writeOne, $FIXED_TYPING_INTERVAL_MS);
              };
              writeOne();
              return JSON.stringify({ok:true});
            })();
        """.trimIndent()

        webView.evaluateJavascript(script) { raw ->
            if (raw.contains("ok\\\":false") || raw.contains("No element at input")) {
                callback(false, "Could not find an editable input at the saved coordinate")
                return@evaluateJavascript
            }
            val delay = message.length * FIXED_TYPING_INTERVAL_MS + CLICK_AFTER_TYPING_PADDING_MS
            mainHandler.postDelayed({
                clickAt(webView, mapping.sendX, mapping.sendY, callback)
            }, delay)
        }
    }

    fun waitForResult(
        webView: WebView,
        previousText: String,
        callback: (success: Boolean, response: String, error: String?) -> Unit
    ) {
        val startedAt = SystemClock.elapsedRealtime()
        fun poll() {
            extractLastAssistantText(webView) { current ->
                val changed = current.isNotBlank() && current != previousText
                if (changed) {
                    callback(true, current, null)
                } else if (SystemClock.elapsedRealtime() - startedAt >= RESULT_TIMEOUT_MS) {
                    callback(false, "", "Timed out waiting for an assistant response")
                } else {
                    mainHandler.postDelayed({ poll() }, RESULT_POLL_MS)
                }
            }
        }
        mainHandler.postDelayed({ poll() }, RESULT_POLL_MS)
    }

    fun clearCallbacks() {
        mainHandler.removeCallbacksAndMessages(null)
    }

    private fun clickAt(
        webView: WebView,
        xRatio: Float,
        yRatio: Float,
        callback: (success: Boolean, error: String?) -> Unit
    ) {
        val script = """
            (function() {
              const vw = document.documentElement.clientWidth || window.innerWidth;
              const vh = document.documentElement.clientHeight || window.innerHeight;
              const x = Math.round($xRatio * vw);
              const y = Math.round($yRatio * vh);
              const el = document.elementFromPoint(x, y);
              if (!el) return false;
              const target = el.closest('button,[role="button"],input[type="submit"]') || el;
              target.dispatchEvent(new MouseEvent('mousedown', {bubbles:true, cancelable:true}));
              target.dispatchEvent(new MouseEvent('mouseup', {bubbles:true, cancelable:true}));
              target.click();
              return true;
            })();
        """.trimIndent()
        webView.evaluateJavascript(script) { raw ->
            callback(raw == "true", if (raw == "true") null else "No send element at saved coordinate")
        }
    }

    private fun mappingValuesAreValid(mapping: CoordinateMapping): Boolean =
        listOf(mapping.inputX, mapping.inputY, mapping.sendX, mapping.sendY).all { it in 0f..1f }

    private fun decodeJavascriptString(raw: String?): String {
        if (raw.isNullOrBlank() || raw == "null") return ""
        return runCatching { JSONTokener(raw).nextValue()?.toString() ?: "" }
            .getOrElse {
                raw.removePrefix("\"")
                    .removeSuffix("\"")
                    .replace("\\n", "\n")
                    .replace("\\\"", "\"")
                    .replace("\\\\", "\\")
            }
    }
}
