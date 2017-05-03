#pragma version(1)
#pragma rs java_package_name(com.android.grafika.rsPipe)


// YUV N12
rs_allocation gCurrentFrame;

static int blurRadius = 1;

uint32_t gW;
uint32_t gH;

static uchar4 yuvToRgb(uint32_t x, uint32_t y){
    uchar yps = rsGetElementAt_uchar(gCurrentFrame, x, y);
    uchar u = rsGetElementAt_uchar(gCurrentFrame,(x & ~1),gH + (y>>1));
    uchar v = rsGetElementAt_uchar(gCurrentFrame,(x & ~1)+1,gH + (y>>1));
    return rsYuvToRGBA_uchar4(yps, u, v);
}

uchar4 RS_KERNEL root(uint32_t x, uint32_t y){
    uint4 sum = 0;
    uint count = 0;
    for (int yi = -blurRadius; yi <= blurRadius; ++yi) {
        for (int xi = -blurRadius; xi <= blurRadius; ++xi) {
                sum += convert_uint4(yuvToRgb(x+xi, y+yi));
                ++count;
            }
    }
    return convert_uchar4(sum/count);

    //return yuvToRgb(x, y);
}