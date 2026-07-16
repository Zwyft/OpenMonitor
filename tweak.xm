#include <tunify/Tweak.h>

%hook BSTYCameraViewController

- (void)viewDidAppear:(BOOL)animated {
    %orig;
    // Ensure we only add overlay once
    static BOOL overlayAdded = NO;
    if (!overlayAdded) {
        overlayAdded = YES;
        // Create a small overlay view for secondary stream
        UIView *overlay = [[UIView alloc] initWithFrame:CGRectMake(20, 20, 200, 150)];
        overlay.backgroundColor = [UIColor clearColor];
        overlay.layer.borderColor = [UIColor whiteColor].CGColor;
        overlay.layer.borderWidth = 2.0;
        [self.view addSubview:overlay];
        // Setup secondary camera session
        AVCaptureSession *session = [[AVCaptureSession alloc] init];
        AVCaptureDevice *device = [AVCaptureDevice defaultDeviceWithMediaType:AVMediaTypeVideo];
        NSError *error = nil;
        AVCaptureDeviceInput *input = [AVCaptureDeviceInput deviceInputWithDevice:device error:&error];
        if (!error && [session canAddInput:input]) {
            [session addInput:input];
        }
        AVCaptureVideoPreviewLayer *preview = [AVCaptureVideoPreviewLayer layerWithSession:session];
        preview.frame = overlay.bounds;
        preview.videoGravity = AVLayerVideoGravityResizeAspectFill;
        [overlay.layer addSublayer:preview];
        [session startRunning];
    }
}

%end
