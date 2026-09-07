#include <windows.h>
#include <jvmti.h>
#include <jvmticmlr.h>
#include <cstdio>
#include <cstdint>
#include <mutex>
#include <string>
#include <unordered_map>

namespace {
std::mutex output_mutex;
FILE* output = nullptr;
std::uint64_t sequence = 0;

std::uint64_t epoch_us() {
    FILETIME time;
    GetSystemTimePreciseAsFileTime(&time);
    const auto ticks = (std::uint64_t(time.dwHighDateTime) << 32) | time.dwLowDateTime;
    return (ticks - 116444736000000000ULL) / 10;
}

std::string clean(const char* value) {
    std::string result = value ? value : "<unavailable>";
    for (auto& ch : result) if (ch == '\t' || ch == '\r' || ch == '\n') ch = ' ';
    return result;
}

std::string method_name(jvmtiEnv* env, jmethodID method) {
    char *name = nullptr, *signature = nullptr, *klass_name = nullptr;
    jclass klass = nullptr;
    const auto method_error = env->GetMethodName(method, &name, &signature, nullptr);
    const auto class_error = env->GetMethodDeclaringClass(method, &klass);
    if (class_error == JVMTI_ERROR_NONE) env->GetClassSignature(klass, &klass_name, nullptr);
    const auto result = method_error == JVMTI_ERROR_NONE
        ? clean(klass_name) + "." + clean(name) + clean(signature) : "<unavailable>";
    if (name) env->Deallocate(reinterpret_cast<unsigned char*>(name));
    if (signature) env->Deallocate(reinterpret_cast<unsigned char*>(signature));
    if (klass_name) env->Deallocate(reinterpret_cast<unsigned char*>(klass_name));
    return result;
}

void JNICALL compiled_load(jvmtiEnv* env, jmethodID method, jint size, const void* address,
                          jint, const jvmtiAddrLocationMap*, const void* info) {
    const auto receipt = epoch_us();
    // Scope this cache to one callback: unloaded classes may invalidate/reuse jmethodIDs.
    std::unordered_map<jmethodID, std::string> names;
    auto name = [&](jmethodID id) -> const std::string& {
        auto [it, inserted] = names.try_emplace(id);
        if (inserted) it->second = method_name(env, id);
        return it->second;
    };
    const auto owner = name(method);
    std::string inline_rows;
    for (auto* header = static_cast<const jvmtiCompiledMethodLoadRecordHeader*>(info);
         header; header = header->next) {
        if (header->kind != JVMTI_CMLR_INLINE_INFO || header->majorinfoversion != 1
                || header->minorinfoversion != 0) continue;
        const auto* record = reinterpret_cast<const jvmtiCompiledMethodLoadInlineRecord*>(header);
        for (int i = 0; i < record->numpcs; ++i) {
            const auto& pc = record->pcinfo[i];
            inline_rows += "I\t" + std::to_string(reinterpret_cast<std::uintptr_t>(pc.pc));
            for (int frame = 0; frame < pc.numstackframes; ++frame) {
                inline_rows += "\t" + name(pc.methods[frame]) + "@" + std::to_string(pc.bcis[frame]);
            }
            inline_rows += '\n';
        }
    }
    std::lock_guard lock(output_mutex);
    if (!output) return;
    // A complete load transaction ends in R; the analyzer rejects incomplete files.
    std::fprintf(output, "L\t%llu\t%llu\t%llu\t%d\t%s\n", ++sequence, receipt,
                 reinterpret_cast<std::uintptr_t>(address), size, owner.c_str());
    std::fputs(inline_rows.c_str(), output);
    std::fprintf(output, "R\t%llu\t%llu\n", sequence, epoch_us());
}

void JNICALL compiled_unload(jvmtiEnv*, jmethodID, const void* address) {
    const auto receipt = epoch_us();
    std::lock_guard lock(output_mutex);
    if (output) std::fprintf(output, "U\t%llu\t%llu\n", receipt,
                            reinterpret_cast<std::uintptr_t>(address));
    // Do not query method metadata here: its class may already have been unloaded.
}

void JNICALL dynamic_code(jvmtiEnv*, const char* name, const void* address, jint size) {
    const auto receipt = epoch_us();
    std::lock_guard lock(output_mutex);
    if (output) std::fprintf(output, "D\t%llu\t%llu\t%llu\t%d\t%s\n", ++sequence,
                            receipt, reinterpret_cast<std::uintptr_t>(address), size, clean(name).c_str());
}

void JNICALL vm_death(jvmtiEnv*, JNIEnv*) {
    std::lock_guard lock(output_mutex);
    if (!output) return;
    std::fprintf(output, "E\t%llu\n", epoch_us());
    std::fclose(output);
    output = nullptr;
}
}

extern "C" JNIEXPORT jint JNICALL Agent_OnLoad(JavaVM* vm, char* options, void*) {
    if (!options || !*options) return JNI_ERR;
    // Exclusive creation prevents accidentally overwriting another JVM's map.
    output = std::fopen(options, "wbx");
    if (!output) return JNI_ERR;
    std::setvbuf(output, nullptr, _IOFBF, 1024 * 1024);
    std::fprintf(output, "H\t1\t%lu\t%llu\n", GetCurrentProcessId(), epoch_us());
    jvmtiEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JVMTI_VERSION_1_2) != JNI_OK) return JNI_ERR;
    jvmtiCapabilities capabilities{};
    capabilities.can_generate_compiled_method_load_events = 1;
    if (env->AddCapabilities(&capabilities) != JVMTI_ERROR_NONE) return JNI_ERR;
    jvmtiEventCallbacks callbacks{};
    callbacks.CompiledMethodLoad = compiled_load;
    callbacks.CompiledMethodUnload = compiled_unload;
    callbacks.DynamicCodeGenerated = dynamic_code;
    callbacks.VMDeath = vm_death;
    if (env->SetEventCallbacks(&callbacks, sizeof(callbacks)) != JVMTI_ERROR_NONE) return JNI_ERR;
    for (auto event : {JVMTI_EVENT_COMPILED_METHOD_LOAD, JVMTI_EVENT_COMPILED_METHOD_UNLOAD,
                       JVMTI_EVENT_DYNAMIC_CODE_GENERATED, JVMTI_EVENT_VM_DEATH}) {
        if (env->SetEventNotificationMode(JVMTI_ENABLE, event, nullptr) != JVMTI_ERROR_NONE) return JNI_ERR;
    }
    return JNI_OK;
}
