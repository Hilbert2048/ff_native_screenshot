#import "FfNativeScreenshotPlugin.h"
static FLTScreenshotFlutterApi *screenshotFlutterApi;
@implementation FfNativeScreenshotPlugin
+ (void)registerWithRegistrar:(NSObject<FlutterPluginRegistrar>*)registrar {
    FfNativeScreenshotPlugin* instance = [[FfNativeScreenshotPlugin alloc] init];
    FLTScreenshotHostApiSetup(registrar.messenger,instance);
    screenshotFlutterApi = [[FLTScreenshotFlutterApi alloc] initWithBinaryMessenger: registrar.messenger ];
}


- (void)takeScreenshotWithCompletion:(nonnull void (^)(FlutterStandardTypedData * _Nullable, FlutterError * _Nullable))completion {
    FlutterStandardTypedData *data = [self takeScreenshot];
    completion(data,nil);
}

- (void)startListeningScreenshotWithError:(FlutterError * _Nullable __autoreleasing * _Nonnull)error {
    [[NSNotificationCenter defaultCenter] addObserver: self
                                             selector:@selector(onTakeScreenShoot:)
                                                 name:UIApplicationUserDidTakeScreenshotNotification object:nil];
}


- (void)stopListeningScreenshotWithError:(FlutterError * _Nullable __autoreleasing * _Nonnull)error{
    [[NSNotificationCenter defaultCenter] removeObserver: self name:UIApplicationUserDidTakeScreenshotNotification object:nil];
}

- (void)onTakeScreenShoot:(NSNotification *)notification{
    
    FlutterStandardTypedData *data = [self takeScreenshot];
    [screenshotFlutterApi onTakeScreenshotData:data completion:^(NSError * _Nullable error) {
        
    }];
}

- (FlutterStandardTypedData *)takeScreenshot {
    @try {
        UIApplication *app = [UIApplication sharedApplication];
        UIWindow *window = [[app delegate] window];
        if (!window) {
             if (@available(iOS 13.0, *)) {
                 for (UIScene *scene in [app connectedScenes]) {
                     if ([scene isKindOfClass:[UIWindowScene class]]) {
                         UIWindowScene *windowScene = (UIWindowScene *)scene;
                         for (UIWindow *w in windowScene.windows) {
                             if ([w isKeyWindow]) {
                                 window = w;
                                 break;
                             }
                         }
                     }
                     if (window) break;
                 }
             }
        }
        
        // Fallback for older iOS versions or if scene finding failed
        if (!window) {
             for (UIWindow *w in [app windows]) {
                 if ([w isKeyWindow]) {
                     window = w;
                     break;
                 }
             }
        }
        
        UIView *view = window;
        if (!view || view.bounds.size.width <= 0 || view.bounds.size.height <= 0) {
             NSLog(@"[FfNativeScreenshotPlugin] Error: View is nil or has zero size. Window: %@", window);
             return nil;
        }

        UIGraphicsImageRendererFormat *format = [UIGraphicsImageRendererFormat defaultFormat];
        format.scale = window.screen.scale;
        format.opaque = [view isOpaque];
        UIGraphicsImageRenderer *renderer = [[UIGraphicsImageRenderer alloc] initWithSize:view.bounds.size format:format];
        
        UIImage *image = [renderer imageWithActions:^(UIGraphicsImageRendererContext * _Nonnull rendererContext) {
            [view drawViewHierarchyInRect:view.bounds afterScreenUpdates:TRUE];
        }];
        
        
        NSData *imageData= UIImageJPEGRepresentation(image, 1.0f);
        FlutterStandardTypedData *data  = [FlutterStandardTypedData typedDataWithBytes:imageData];
        return  data;
    } @catch (NSException *exception) {
        NSLog(@"%@",exception);
        return nil;
    } @finally {

    }

}

@end

