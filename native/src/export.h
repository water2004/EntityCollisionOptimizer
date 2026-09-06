#pragma once

#ifdef AR_WINDOWS
#define ECO_EXPORT __declspec(dllexport)
#else
#define ECO_EXPORT __attribute__((visibility("default")))
#endif