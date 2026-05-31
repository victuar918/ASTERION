    suspend fun init(onProgress: (String)->Unit = {}) = withContext(Dispatchers.IO) {
        val dir = prepareModelDir(onProgress)
        onProgress("🔧 ONNX Runtime 초기화...")
        env = OrtEnvironment.getEnvironment()
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(4)
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        val p = dir.absolutePath
        textEncoder       = env!!.createSession("$p/onnx/text_encoder.onnx",       opts)
        durationPredictor = env!!.createSession("$p/onnx/duration_predictor.onnx", opts)
        vectorEstimator   = env!!.createSession("$p/onnx/vector_estimator.onnx",   opts)
        vocoder           = env!!.createSession("$p/onnx/vocoder.onnx",            opts)

        // 실제 입출력 이름 로그 출력
        fun logIO(name: String, sess: OrtSession) {
            val inputs  = sess.inputNames.joinToString()
            val outputs = sess.outputNames.joinToString()
            Log.i(TAG, "$name IN=[$inputs] OUT=[$outputs]")
            onProgress("$name\nIN:$inputs\nOUT:$outputs")
        }
        logIO("text_encoder",       textEncoder!!)
        logIO("duration_predictor", durationPredictor!!)
        logIO("vector_estimator",   vectorEstimator!!)
        logIO("vocoder",            vocoder!!)

        unicodeIndexer = loadUnicodeIndexer(File(dir, "onnx/unicode_indexer.json"))
        ttsConfig      = JSONObject(File(dir, "onnx/tts.json").readText())
        onProgress("✅ Supertonic 2 준비 (steps=$totalSteps, ${unicodeIndexer.size}자)")
    }
