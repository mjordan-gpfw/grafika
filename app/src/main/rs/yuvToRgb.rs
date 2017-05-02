#pragma version(1)
#pragma rs java_package_name(com.android.grafika.rsPipe)

rs_allocation gCurrentFrame;

uchar4 RS_KERNEL root(uint32_t x, uint32_t y){
      uchar4 curPixel;
      curPixel.r = rsGetElementAtYuv_uchar_Y(gCurrentFrame, x, y);
      curPixel.g = rsGetElementAtYuv_uchar_U(gCurrentFrame, x, y);
      curPixel.b = rsGetElementAtYuv_uchar_V(gCurrentFrame, x, y);
      curPixel.a = 255;
      return curPixel;
}