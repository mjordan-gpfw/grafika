#pragma version(1)
#pragma rs java_package_name(com.android.grafika.rsPipe)

// YUV N12
rs_allocation gCurrentFrame;

uint32_t gW;
uint32_t gH;

uchar4 RS_KERNEL root(uint32_t x, uint32_t y){

    uchar yps = rsGetElementAt_uchar(gCurrentFrame, x, y);
    uchar u = rsGetElementAt_uchar(gCurrentFrame,(x & ~1),gH + (y>>1));
    uchar v = rsGetElementAt_uchar(gCurrentFrame,(x & ~1)+1,gH + (y>>1));
    uchar4 rgb = rsYuvToRGBA_uchar4(yps, u, v);
    return rgb;
}