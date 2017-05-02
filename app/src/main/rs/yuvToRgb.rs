#pragma version(1)
#pragma rs java_package_name(com.android.grafika.rsPipe)

// YUV N21
rs_allocation gCurrentFrame;

uint32_t gW;
uint32_t gH;

uchar4 RS_KERNEL root(uint32_t x, uint32_t y){

    uchar yp = rsGetElementAt_uchar(gCurrentFrame, x, y);

    int frameSize = gW*gH;
    int index = frameSize + (x & (~1)) + (( y>>1) * gW );

    int v = (int)( rsGetElementAt_uchar(gCurrentFrame, index) & 0xFF ) -128;
    int u = (int)( rsGetElementAt_uchar(gCurrentFrame, index+1) & 0xFF ) -128;

    int r = (int) (1.164f * yp  + 1.596f * v );
    int g = (int) (1.164f * yp  - 0.813f * v  - 0.391f * u);
    int b = (int) (1.164f * yp  + 2.018f * u );

    r = r>255? 255 : r<0 ? 0 : r;
    g = g>255? 255 : g<0 ? 0 : g;
    b = b>255? 255 : b<0 ? 0 : b;

    uchar4 res4;
    res4.r = (uchar)r;
    res4.g = (uchar)g;
    res4.b = (uchar)b;
    res4.a = 0xFF;

    return res4;
}