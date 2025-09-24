import ExpoModulesCore

public class ExpoMp4MuxerModule: Module {
  public func definition() -> ModuleDefinition {
    Name("ExpoMp4Muxer")

    Function("getTheme") { () -> String in 
      "system"
    }
  }
}
