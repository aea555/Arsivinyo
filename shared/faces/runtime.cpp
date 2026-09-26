// YuNet and SFace through ONNX Runtime's C API. The same version on both apps
// (MODELS.json), so the same file gives the same numbers.

#include "faces.h"

#include <onnxruntime_c_api.h>

#include <mutex>

namespace faces {

namespace {

const OrtApi& api() {
    static const OrtApi* instance = OrtGetApiBase()->GetApi(ORT_API_VERSION);
    return *instance;
}

/** One environment per process, as ONNX Runtime asks. */
OrtEnv* environment(std::string* error) {
    static OrtEnv* env = nullptr;
    static std::once_flag once;
    static std::string failure;
    std::call_once(once, [] {
        if (OrtStatus* status = api().CreateEnv(ORT_LOGGING_LEVEL_ERROR, "faces", &env)) {
            failure = api().GetErrorMessage(status);
            api().ReleaseStatus(status);
            env = nullptr;
        }
    });
    if (!env && error) *error = failure;
    return env;
}

/** Turns a status into a message and frees it. True when there was an error. */
bool failed(OrtStatus* status, std::string* error) {
    if (!status) return false;
    if (error) *error = api().GetErrorMessage(status);
    api().ReleaseStatus(status);
    return true;
}

class OrtModels final : public Models {
public:
    ~OrtModels() override {
        if (detector_) api().ReleaseSession(detector_);
        if (recogniser_) api().ReleaseSession(recogniser_);
        if (memory_) api().ReleaseMemoryInfo(memory_);
    }

    bool open(const std::vector<uint8_t>& detector, const std::vector<uint8_t>& recogniser, std::string* error) {
        OrtEnv* env = environment(error);
        if (!env) return false;
        OrtSessionOptions* options = nullptr;
        if (failed(api().CreateSessionOptions(&options), error)) return false;
        // Two threads: a scan runs in the background and should not heat the phone.
        const bool ok =
            !failed(api().SetIntraOpNumThreads(options, 2), error) &&
            !failed(api().SetInterOpNumThreads(options, 1), error) &&
            !failed(api().SetSessionGraphOptimizationLevel(options, ORT_ENABLE_ALL), error) &&
            !failed(api().CreateSessionFromArray(env, detector.data(), detector.size(), options, &detector_), error) &&
            !failed(api().CreateSessionFromArray(env, recogniser.data(), recogniser.size(), options, &recogniser_),
                    error) &&
            !failed(api().CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &memory_), error);
        api().ReleaseSessionOptions(options);
        return ok;
    }

    std::vector<Detection> detect(const Image& image) override {
        float scale = 1;
        Planar input = detectorInput(image, &scale);
        static const char* inputs[] = {"input"};
        static const char* outputs[] = {"cls_8",  "cls_16",  "cls_32",  "obj_8", "obj_16", "obj_32",
                                        "bbox_8", "bbox_16", "bbox_32", "kps_8", "kps_16", "kps_32"};
        OrtValue* results[12] = {};
        if (!run(detector_, inputs[0], input, outputs, 12, results)) return {};
        std::vector<YuNetLevel> levels;
        const int strides[3] = {8, 16, 32};
        for (int i = 0; i < 3; ++i) {
            YuNetLevel level;
            level.stride = strides[i];
            level.cls = data(results[i]);
            level.obj = data(results[3 + i]);
            level.bbox = data(results[6 + i]);
            level.kps = data(results[9 + i]);
            levels.push_back(level);
        }
        std::vector<Detection> found;
        bool complete = true;
        for (const auto& l : levels) complete = complete && l.cls && l.obj && l.bbox && l.kps;
        if (complete) found = decodeYuNet(levels, scale);
        for (auto* value : results) {
            if (value) api().ReleaseValue(value);
        }
        return found;
    }

    Signature embed(const Image& image, const Detection& face) override {
        Planar input = recogniserInput(image, face);
        static const char* inputs[] = {"data"};
        static const char* outputs[] = {"fc1"};
        OrtValue* result = nullptr;
        Signature signature{};
        if (!run(recogniser_, inputs[0], input, outputs, 1, &result)) return signature;
        if (const float* values = data(result)) signature = normalised(values);
        api().ReleaseValue(result);
        return signature;
    }

private:
    OrtSession* detector_ = nullptr;
    OrtSession* recogniser_ = nullptr;
    OrtMemoryInfo* memory_ = nullptr;

    bool run(OrtSession* session, const char* inputName, Planar& input, const char* const* outputNames,
             size_t outputCount, OrtValue** results) {
        const int64_t shape[4] = {1, 3, input.height, input.width};
        OrtValue* tensor = nullptr;
        if (failed(api().CreateTensorWithDataAsOrtValue(memory_, input.data.data(), input.data.size() * sizeof(float),
                                                        shape, 4, ONNX_TENSOR_ELEMENT_DATA_TYPE_FLOAT, &tensor),
                   nullptr)) {
            return false;
        }
        const OrtValue* in[] = {tensor};
        const bool ok = !failed(api().Run(session, nullptr, &inputName, in, 1, outputNames, outputCount, results),
                                nullptr);
        api().ReleaseValue(tensor);
        return ok;
    }

    static const float* data(OrtValue* value) {
        if (!value) return nullptr;
        float* out = nullptr;
        if (failed(api().GetTensorMutableData(value, reinterpret_cast<void**>(&out)), nullptr)) return nullptr;
        return out;
    }
};

}  // namespace

std::unique_ptr<Models> Models::load(const std::vector<uint8_t>& detector, const std::vector<uint8_t>& recogniser,
                                     std::string* error) {
    auto models = std::make_unique<OrtModels>();
    if (!models->open(detector, recogniser, error)) return nullptr;
    return models;
}

}  // namespace faces
