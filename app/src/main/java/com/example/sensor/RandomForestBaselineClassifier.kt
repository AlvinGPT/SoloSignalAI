package com.example.sensor

import android.content.Context
import com.example.domain.MovementCategory
import java.io.File
import org.json.JSONObject

/**
 * Result of evaluating the Random Forest baseline comparator on a 36-feature window.
 * NOTE: Strictly used for offline and benchmark comparison; never used in live runtime alerting.
 */
data class RfBaselinePrediction(
    val modelVersion: String,
    val predictedCategory: MovementCategory,
    val confidence: Float,
    val classProbabilities: Map<MovementCategory, Float>,
    val inferenceLatencyMs: Double
)

/**
 * Random Forest baseline classifier operating on 36 statistical & spectral window features.
 * Kept strictly as an evaluation baseline to compare against the 1D CNN and 3-Phase Rule Detector.
 */
class RandomForestBaselineClassifier(
    context: Context? = null,
    assetPath: String = DEFAULT_RF_ASSET_PATH
) {
    companion object {
        const val DEFAULT_RF_ASSET_PATH = "models/solosignal_rf_baseline_v1.json"
        private const val NUM_CLASSES = 6
    }

    private sealed class TreeNode {
        data class Leaf(val probs: FloatArray) : TreeNode()
        data class Split(
            val featureIndex: Int,
            val threshold: Float,
            val left: TreeNode,
            val right: TreeNode
        ) : TreeNode()
    }

    var modelVersion: String = "solosignal-rf-baseline-v1.0.0"
        private set

    private val trees = mutableListOf<TreeNode>()

    val isLoaded: Boolean
        get() = trees.isNotEmpty()

    init {
        loadTrees(context, assetPath)
    }

    private fun loadTrees(context: Context?, assetPath: String) {
        val rawText = readAssetOrFileText(context, assetPath) ?: return
        try {
            val root = JSONObject(rawText)
            modelVersion = root.optString("modelVersion", "solosignal-rf-baseline-v1.0.0")
            val treesArr = root.optJSONArray("trees") ?: return
            trees.clear()
            for (i in 0 until treesArr.length()) {
                val nodeObj = treesArr.optJSONObject(i) ?: continue
                trees.add(parseNode(nodeObj))
            }
            if (trees.isNotEmpty()) return
        } catch (_: Throwable) {
            trees.clear()
        }

        // Pure-Kotlin recursive descent parser fallback for plain JVM JUnit tests where android.jar JSONObject is a stub
        try {
            val verMatch = Regex("\"modelVersion\"\\s*:\\s*\"([^\"]*)\"").find(rawText)
            if (verMatch != null) {
                modelVersion = verMatch.groupValues[1]
            }
            val treesKeyIdx = rawText.indexOf("\"trees\"")
            if (treesKeyIdx >= 0) {
                val arrStart = rawText.indexOf('[', treesKeyIdx)
                if (arrStart >= 0) {
                    var idx = arrStart + 1
                    trees.clear()
                    while (idx < rawText.length) {
                        while (idx < rawText.length && (rawText[idx].isWhitespace() || rawText[idx] == ',')) idx++
                        if (idx >= rawText.length || rawText[idx] == ']') break
                        if (rawText[idx] == '{') {
                            val objEnd = findMatchingBrace(rawText, idx, '{', '}')
                            if (objEnd > idx) {
                                val nodeText = rawText.substring(idx, objEnd + 1)
                                trees.add(parseNodeFromText(nodeText))
                                idx = objEnd + 1
                            } else {
                                break
                            }
                        } else {
                            idx++
                        }
                    }
                }
            }
        } catch (_: Throwable) {
            trees.clear()
        }
    }

    private fun findMatchingBrace(s: String, start: Int, openChar: Char, closeChar: Char): Int {
        var depth = 0
        var inStr = false
        var i = start
        while (i < s.length) {
            val ch = s[i]
            if (ch == '"' && (i == 0 || s[i - 1] != '\\')) {
                inStr = !inStr
            } else if (!inStr) {
                if (ch == openChar) depth++
                else if (ch == closeChar) {
                    depth--
                    if (depth == 0) return i
                }
            }
            i++
        }
        return -1
    }

    private fun parseNodeFromText(nodeText: String): TreeNode {
        val isLeaf = Regex("\"leaf\"\\s*:\\s*(true|false)").find(nodeText)?.groupValues?.get(1) == "true"
        if (isLeaf) {
            val probsIdx = nodeText.indexOf("\"probs\"")
            val arrStart = if (probsIdx >= 0) nodeText.indexOf('[', probsIdx) else -1
            val arrEnd = if (arrStart >= 0) nodeText.indexOf(']', arrStart) else -1
            if (arrStart >= 0 && arrEnd > arrStart) {
                val parts = nodeText.substring(arrStart + 1, arrEnd).split(',')
                val probs = FloatArray(NUM_CLASSES) { c ->
                    parts.getOrNull(c)?.trim()?.toFloatOrNull() ?: (1f / NUM_CLASSES)
                }
                return TreeNode.Leaf(probs)
            }
            return TreeNode.Leaf(FloatArray(NUM_CLASSES) { 1f / NUM_CLASSES })
        }
        val feat = Regex("\"feature\"\\s*:\\s*(\\d+)").find(nodeText)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val thresh = Regex("\"threshold\"\\s*:\\s*([-+0-9.eE]+)").find(nodeText)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f

        val leftKey = nodeText.indexOf("\"left\"")
        val leftBraceStart = if (leftKey >= 0) nodeText.indexOf('{', leftKey) else -1
        val leftBraceEnd = if (leftBraceStart >= 0) findMatchingBrace(nodeText, leftBraceStart, '{', '}') else -1

        val rightKey = if (leftBraceEnd >= 0) nodeText.indexOf("\"right\"", leftBraceEnd) else nodeText.indexOf("\"right\"")
        val rightBraceStart = if (rightKey >= 0) nodeText.indexOf('{', rightKey) else -1
        val rightBraceEnd = if (rightBraceStart >= 0) findMatchingBrace(nodeText, rightBraceStart, '{', '}') else -1

        val leftNode = if (leftBraceStart >= 0 && leftBraceEnd > leftBraceStart) {
            parseNodeFromText(nodeText.substring(leftBraceStart, leftBraceEnd + 1))
        } else {
            TreeNode.Leaf(FloatArray(NUM_CLASSES) { 1f / NUM_CLASSES })
        }
        val rightNode = if (rightBraceStart >= 0 && rightBraceEnd > rightBraceStart) {
            parseNodeFromText(nodeText.substring(rightBraceStart, rightBraceEnd + 1))
        } else {
            TreeNode.Leaf(FloatArray(NUM_CLASSES) { 1f / NUM_CLASSES })
        }
        return TreeNode.Split(feat, thresh, leftNode, rightNode)
    }

    private fun parseNode(obj: JSONObject): TreeNode {
        if (obj.optBoolean("leaf", true)) {
            val arr = obj.optJSONArray("probs")
            val probs = FloatArray(NUM_CLASSES) { c ->
                arr?.optDouble(c, 1.0 / NUM_CLASSES)?.toFloat() ?: (1f / NUM_CLASSES)
            }
            return TreeNode.Leaf(probs)
        }
        val feat = obj.optInt("feature", 0)
        val thresh = obj.optDouble("threshold", 0.0).toFloat()
        val left = parseNode(obj.getJSONObject("left"))
        val right = parseNode(obj.getJSONObject("right"))
        return TreeNode.Split(feat, thresh, left, right)
    }

    fun predict(features36: FloatArray): RfBaselinePrediction {
        val startNs = System.nanoTime()
        if (trees.isEmpty() || features36.size < 36) {
            val elapsed = (System.nanoTime() - startNs) / 1_000_000.0
            return RfBaselinePrediction(
                modelVersion = modelVersion,
                predictedCategory = MovementCategory.UNKNOWN_UNCERTAIN,
                confidence = 0f,
                classProbabilities = emptyMap(),
                inferenceLatencyMs = elapsed
            )
        }

        val agg = FloatArray(NUM_CLASSES)
        val nTrees = trees.size.toFloat()
        for (tree in trees) {
            val leafProbs = evaluateTree(tree, features36)
            for (c in 0 until NUM_CLASSES) {
                agg[c] += leafProbs[c] / nTrees
            }
        }

        var bestIdx = 0
        var bestProb = agg[0]
        val probMap = LinkedHashMap<MovementCategory, Float>()
        for (c in 0 until NUM_CLASSES) {
            if (agg[c] > bestProb) {
                bestProb = agg[c]
                bestIdx = c
            }
            probMap[MovementCategory.fromIndex(c)] = agg[c]
        }

        val elapsed = (System.nanoTime() - startNs) / 1_000_000.0
        return RfBaselinePrediction(
            modelVersion = modelVersion,
            predictedCategory = MovementCategory.fromIndex(bestIdx),
            confidence = bestProb,
            classProbabilities = probMap,
            inferenceLatencyMs = elapsed
        )
    }

    private fun evaluateTree(node: TreeNode, features: FloatArray): FloatArray {
        var cur = node
        while (cur is TreeNode.Split) {
            cur = if (features[cur.featureIndex] <= cur.threshold) cur.left else cur.right
        }
        return (cur as TreeNode.Leaf).probs
    }

    private fun readAssetOrFileText(context: Context?, relativeAssetPath: String): String? {
        if (context != null) {
            try {
                context.assets.open(relativeAssetPath).use { return it.readBytes().toString(Charsets.UTF_8) }
            } catch (_: Throwable) {
            }
        }
        val candidates = listOf(
            File("app/src/main/assets/$relativeAssetPath"),
            File("src/main/assets/$relativeAssetPath"),
            File(relativeAssetPath)
        )
        for (f in candidates) {
            if (f.exists() && f.isFile) {
                return try {
                    f.readText(Charsets.UTF_8)
                } catch (_: Throwable) {
                    null
                }
            }
        }
        return null
    }
}
