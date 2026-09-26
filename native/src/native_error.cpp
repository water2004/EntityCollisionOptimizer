#include "native_error.h"
#include "eco/collision_api.h"

#include <cstddef>
#include <exception>
#include <new>
#include <stdexcept>

namespace {

thread_local char lastError[256] = {};

void writeError(const char* type, const char* message) noexcept {
    std::size_t length = 0;
    const auto append = [&](const char* text) {
        while (*text != '\0' && length + 1 < sizeof(lastError)) {
            lastError[length++] = *text++;
        }
    };
    append(type);
    if (message != nullptr && *message != '\0') {
        append(": ");
        append(message);
    }
    lastError[length] = '\0';
}

} // namespace

int eco::recordNativeException() noexcept {
    try {
        throw;
    } catch (const std::bad_alloc& error) {
        writeError("std::bad_alloc", error.what());
    } catch (const std::length_error& error) {
        writeError("std::length_error", error.what());
    } catch (const std::exception& error) {
        writeError("std::exception", error.what());
    } catch (...) {
        writeError("unknown C++ exception", nullptr);
    }
    return NATIVE_EXCEPTION_STATUS;
}

const char* eco::lastNativeException() noexcept {
    return lastError;
}

const char* lastNativeException() noexcept {
    return eco::lastNativeException();
}
