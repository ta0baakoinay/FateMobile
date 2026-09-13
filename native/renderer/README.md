# native/renderer

OpenGL ES sprite batching, texture atlases, frustum culling, camera
(docs/FATE_MMO_MOBILE_ARCHITECTURE.md §5).

**Status:** still empty beyond the Phase 0/1 JNI stub in native/src/native_lib.cpp
— no OpenGL ES / native rendering code exists here yet. Phase 3's actual
top-down rendering (real map + character sprite + wall collision) lives at
the Android layer instead, in android/app/.../world/GameMapView.kt, using
Canvas — a deliberate, documented interim choice (see that file's class doc
and docs/FATE_MMO_MOBILE_ROADMAP.md Phase 3b), not a renderer implemented
here. Migrating to a native OpenGL ES renderer remains future work once
gameplay/asset needs outgrow what Canvas can do.
