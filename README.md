# ZaxConvert

A Minecraft plugin framework for converting Java resource packs to Bedrock format with support for popular plugins like Nexo, ItemsAdder, and Oraxen. Built for use with GeyserMC to enable cross-platform compatibility.

## Features

- Converts Java resource packs to Bedrock-compatible format
- Automatic support for Nexo, ItemsAdder, and Oraxen plugins
- GeyserMC integration for cross-platform play
- Simple command interface: `/zc convert` and `/zinv`
- Configuration-driven plugin support
- Automatic plugin configuration updates
- Resource pack backup and safety features

## Installation

1. Download the latest ZaxConvert JAR file
2. Place it in your server's `plugins` directory
3. Restart or reload your server
4. Configure the plugin as needed (optional)
5. Use the commands to convert your resource packs

## Commands

### Main Commands

```
/zc convert <resourcepack> [outputdir]
    Converts a Java resource pack to Bedrock format
    <resourcepack>: Path to Java resource pack (zip file or folder)
    [outputdir]: Optional output directory (defaults to Geyser packs folder)

/zc reload
    Reloads the plugin configuration

/zc help
    Shows help information

/zc info
    Shows plugin information and version

/zc setup
    Runs initial setup (creates directories, etc.)
```

### Aliases

```
/zinv
    Same as /zc (provided for inventory management convenience)
```

### Examples

```
/zc convert plugins/ZaxConvert/resourcepacks/myresourcepack.zip
/zc convert ./myresourcepack ./Geyser/packs
/zc convert ~/Desktop/resourcepacks/advancedpack.zip ../Geyser/packs
```

## Configuration

The plugin generates a `config.yml` file in its directory with the following options:

```yaml
# ZaxConvert Configuration
general:
  backup-enabled: true
  verbose-logging: false
  default-resource-pack: "plugins/ZaxConvert/resourcepacks/"
  geyser-output-dir: "../Geyser/"

plugin-support:
  nexo-enabled: true
  itemsadder-enabled: true
  oraxen-enabled: true
  auto-configure-plugins: true

conversion:
  max-file-size: 100
  texture-optimization: true
  preserve-custom-model-data: true
  convert-custom-effects: true

geyser:
  auto-place-files: true
  custom-mappings-folder: "custom_mappings"
  resource-packs-folder: "packs"
  proxy-mode-support: true

advanced:
  debug-mode: false
  experimental-features: false
```

## How It Works

ZaxConvert works by:

1. **Analyzing** your Java resource pack to detect used plugins (Nexo, ItemsAdder, Oraxen)
2. **Processing** textures, models, and other assets for Bedrock compatibility
3. **Generating** a Bedrock-compatible resource pack with proper format
4. **Creating** Geyser mapping files for custom items/blocks
5. **Updating** plugin configurations to work with the converted resources
6. **Outputting** everything to the specified directory for GeyserMC use

## Supported Plugins

- **Nexo** - Full support for custom items, blocks, and entities
- **ItemsAdder** - Complete conversion of custom items, furniture, and packs
- **Oraxen** - Full support for custom items, weapons, armor, and more

## Requirements

- Minecraft server running Paper/Spigot 1.21+
- GeyserMC installed for Bedrock player support
- Java 21 or higher
- Resource pack created with Nexo, ItemsAdder, or Oraxen (optional but recommended)

## How to Use

1. Prepare your Java resource pack (ensure it works for Java players first)
2. Run `/zc convert <path-to-your-resource-pack>`
3. Wait for the conversion process to complete
4. Copy the generated files to your GeyserMC server:
   - Place contents in `Geyser/packs/` for the resource pack
   - Place mapping files in `Geyser/custom_mappings/`
5. Restart your server
6. Bedrock players will now see your custom content!

## Command Aliases Explained

The framework provides two main commands that function identically:

- `/zc` - Main conversion command
- `/zinv` - Alias for inventory management (functions same as `/zc`)

Both commands support the same subcommands:
- `convert` - Start a resource pack conversion
- `reload` - Reload plugin configuration
- `help` - Show help information
- `info` - Show plugin information
- `setup` - Run initial setup

## Permissions

- `zaxconvert.use` - Base permission to use ZaxConvert commands (default: true)
- `zaxconvert.convert` - Permission to use `/zc convert` (default: op)
- `zaxconvert.reload` - Permission to use `/zc reload` (default: op)
- `zaxconvert.setup` - Permission to use `/zc setup` (default: op)

## Support

For issues, questions, or feature requests, please visit:
https://github.com/zaxconvert/ZaxConvert

## License

This project is licensed under the MIT License - see the LICENSE file for details.