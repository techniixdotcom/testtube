#define NAN __builtin_nanf("")
#define INFINITY __builtin_inff()
#define isnan(x) __builtin_isnan(x)
#define isfinite(x) __builtin_isfinite(x)
