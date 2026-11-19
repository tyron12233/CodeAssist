#import <Foundation/Foundation.h>

NS_ASSUME_NONNULL_BEGIN

/// The JVM this app embeds, for the Kotlin framework to reach the IDE's JVM backend through.
///
/// Present in every build; `start` answers nil in one built without the JVM (no `CODEASSIST_JVM_SDK`), and
/// the app then runs as it always has, with no layout editor.
@interface JvmHost : NSObject

/// An object conforming to the framework's `EmbeddedJvm` protocol, or nil when this build carries no JVM.
/// The JVM itself is started the first time something is asked of it.
+ (nullable id)start;

@end

NS_ASSUME_NONNULL_END
