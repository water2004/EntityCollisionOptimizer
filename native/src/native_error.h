#pragma once

namespace eco {

// Reserved for C++ exceptions translated at the FFM boundary.
inline constexpr int NATIVE_EXCEPTION_STATUS = -100;

// Called only from an active catch handler; neither function allocates.
int recordNativeException() noexcept;
const char* lastNativeException() noexcept;

} // namespace eco
