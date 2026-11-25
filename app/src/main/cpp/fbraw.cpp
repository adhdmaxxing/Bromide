// Bromide RAW decoding: LibRaw -> linear Rec.2020 16-bit RGB + embedded JPEG.
// JNI entry point for com.bromide.engine.RawDecoder.

#include <jni.h>
#include <cstring>
#include <memory>
#include <string>

#include "libraw/libraw.h"

namespace {

void setInt(JNIEnv* env, jobject obj, jclass cls, const char* name, int v) {
    env->SetIntField(obj, env->GetFieldID(cls, name, "I"), v);
}

jstring err(JNIEnv* env, const char* what, int code) {
    std::string m = std::string(what) + ": " + libraw_strerror(code);
    return env->NewStringUTF(m.c_str());
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_bromide_engine_RawDecoder_nativeDecode(JNIEnv* env, jclass, jstring jpath, jboolean half, jobject out) {
    const char* path = env->GetStringUTFChars(jpath, nullptr);
    std::unique_ptr<LibRaw> raw(new LibRaw());
    int rc = raw->open_file(path);
    env->ReleaseStringUTFChars(jpath, path);
    if (rc != LIBRAW_SUCCESS) return err(env, "open", rc);

    jclass cls = env->GetObjectClass(out);

    // Embedded JPEG preview: lets the app match the camera's own exposure.
    if (raw->unpack_thumb() == LIBRAW_SUCCESS && raw->imgdata.thumbnail.tformat == LIBRAW_THUMBNAIL_JPEG &&
        raw->imgdata.thumbnail.tlength > 0) {
        jsize n = (jsize) raw->imgdata.thumbnail.tlength;
        jbyteArray th = env->NewByteArray(n);
        env->SetByteArrayRegion(th, 0, n, reinterpret_cast<const jbyte*>(raw->imgdata.thumbnail.thumb));
        env->SetObjectField(out, env->GetFieldID(cls, "thumbnail", "[B"), th);
        env->DeleteLocalRef(th);
    }

    libraw_output_params_t& p = raw->imgdata.params;
    p.output_color = 8;      // Rec.2020
    p.gamm[0] = 1.0;         // linear
    p.gamm[1] = 1.0;
    p.no_auto_bright = 1;    // keep the camera's exposure
    p.output_bps = 16;
    p.use_camera_wb = 1;     // white balance as shot
    p.half_size = half ? 1 : 0;
    p.user_qual = 3;         // AHD demosaic when full size
    p.highlight = 0;         // clip blown highlights to white

    if ((rc = raw->unpack()) != LIBRAW_SUCCESS) return err(env, "unpack", rc);
    if ((rc = raw->dcraw_process()) != LIBRAW_SUCCESS) return err(env, "process", rc);
    int memErr = 0;
    libraw_processed_image_t* img = raw->dcraw_make_mem_image(&memErr);
    if (!img) return err(env, "image", memErr);
    if (img->type != LIBRAW_IMAGE_BITMAP || img->colors != 3 || img->bits != 16) {
        LibRaw::dcraw_clear_mem(img);
        return env->NewStringUTF("unexpected image format");
    }
    jsize count = (jsize) img->width * img->height * 3;
    jshortArray rgb = env->NewShortArray(count);
    if (!rgb) {
        LibRaw::dcraw_clear_mem(img);
        return env->NewStringUTF("out of memory");
    }
    env->SetShortArrayRegion(rgb, 0, count, reinterpret_cast<const jshort*>(img->data));
    env->SetObjectField(out, env->GetFieldID(cls, "rgb", "[S"), rgb);
    env->DeleteLocalRef(rgb);
    setInt(env, out, cls, "width", img->width);
    setInt(env, out, cls, "height", img->height);
    LibRaw::dcraw_clear_mem(img);

    // The decoded image is already rotated; the JPEG preview is stored unrotated.
    int flip = raw->imgdata.sizes.flip;
    int deg = flip == 3 ? 180 : flip == 5 ? 270 : flip == 6 ? 90 : 0;
    setInt(env, out, cls, "thumbFlip", deg);

    std::string cam = std::string(raw->imgdata.idata.make) + " " + raw->imgdata.idata.model;
    jstring jcam = env->NewStringUTF(cam.c_str());
    env->SetObjectField(out, env->GetFieldID(cls, "camera", "Ljava/lang/String;"), jcam);
    env->DeleteLocalRef(jcam);
    return nullptr;
}
