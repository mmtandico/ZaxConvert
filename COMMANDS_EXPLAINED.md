# ZaxConvert Command Explanation

## Overview
ZaxConvert provides two primary commands that function identically:
- `/zc` - Main conversion command
- `/zinv` - Inventory management alias (functions same as `/zc`)

Both commands support the same subcommands and functionality.

## Command Structure

### Base Commands
```
/zc [subcommand] [arguments]
/zinv [subcommand] [arguments]
```

### Available Subcommands

#### 1. `/zc convert <resourcepack> [outputdir]`
**Purpose:** Converts a Java resource pack to Bedrock format for use with GeyserMC

**Arguments:**
- `<resourcepack>`: Path to the Java resource pack (can be a .zip file or folder)
- `[outputdir]`: Optional output directory (defaults to Geyser's packs folder if not specified)

**Functionality:**
- Analyzes the resource pack for used plugins (Nexo, ItemsAdder, Oraxen)
- Processes textures, models, and assets for Bedrock compatibility
- Generates Bedrock-compatible resource pack
- Creates Geyser mapping files
- Updates plugin configurations to work with converted resources
- Outputs everything to the specified directory

**Examples:**
```
/zc convert plugins/ZaxConvert/resourcepacks/myresourcepack.zip
/zc convert ./myresourcepack ./Geyser/packs
/zc convert ~/Desktop/resourcepacks/advancedpack.zip ../Geyser/packs
```

#### 2. `/zc reload`
**Purpose:** Reloads the plugin configuration from config.yml

**Arguments:** None

**Functionality:**
- Refreshes all plugin settings
- Useful after changing configuration manually
- Applies new settings without restarting server

**Example:**
```
/zc reload
```

#### 3. `/zc help`
**Purpose:** Displays help information about available commands

**Arguments:** None

**Functionality:**
- Shows all available subcommands and their usage
- Provides examples and explanations

**Example:**
```
/zc help
```

#### 4. `/zc info`
**Purpose:** Shows plugin information, version, and supported features

**Arguments:** None

**Functionality:**
- Displays plugin version and author
- Shows which plugins are enabled/disabled (Nexo, ItemsAdder, Oraxen)
- Displays Geyser integration settings

**Example:**
```
/zc info
```

#### 5. `/zc setup`
**Purpose:** Runs initial setup to create necessary directories

**Arguments:** None

**Functionality:**
- Creates resource packs directory if it doesn't exist
- Prepares the plugin for first use

**Example:**
```
/zc setup
```

## Command Aliases Explained

### Why Two Commands?
The framework provides both `/zc` and `/zinv` commands for user convenience:
- `/zc` stands for "ZaxConvert" - the main command name
- `/zinv` stands for "ZaxConvert Inventory" - provided as an alias for inventory management purposes

Despite having different names, both commands:
- Execute the exact same code
- Support all the same subcommands
- Require the same permissions
- Provide identical functionality

This allows users to use whichever command name they find more intuitive or memorable.

## Permission System

ZaxConvert uses a fine-grained permission system:

| Permission Node | Description | Default |
|----------------|-------------|---------|
| `zaxconvert.use` | Base permission to use ZaxConvert commands | true |
| `zaxconvert.convert` | Permission to use `/zc convert` | op |
| `zaxconvert.reload` | Permission to use `/zc reload` | op |
| `zaxconvert.info` | Permission to use `/zc info` | true |
| `zaxconvert.setup` | Permission to use `/zc setup` | op |
| `zaxconvert.inventory` | Permission to use `/zinv` | true |

## Usage Examples

### Basic Conversion
```
/zc convert ./myawesomepack
```
Converts the resource pack in `./myawesomepack` to Bedrock format and places it in the default Geyser packs folder.

### Specifying Output Directory
```
/zc convert ./myresourcepack ./Geyser/custom_packs/
```
Converts the resource pack and places the output in `./Geyser/custom_packs/` instead of the default location.

### Using the Alias
```
/zinv convert ./myresourcepack
```
Works exactly the same as `/zc convert ./myresourcepack`.

### Reloading Configuration
```
/zc reload
```
Reloads the config.yml file to apply any changes made.

### Checking Plugin Status
```
/zc info
```
Shows which plugins (Nexo, ItemsAdder, Oraxen) are currently enabled and other plugin information.