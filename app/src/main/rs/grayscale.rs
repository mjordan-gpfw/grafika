#pragma version(1)
#pragma rs java_package_name(com.android.grafika.rsPipe)

uchar4 RS_KERNEL root(uchar4 in, uint32_t x, uint32_t y){
  uchar4 out;
  out.r = in.r;
  out.g = in.g;
  out.b = in.b;
  out.a = in.a;
  return out;
}