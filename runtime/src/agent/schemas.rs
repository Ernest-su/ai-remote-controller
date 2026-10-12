use serde_json::Value;

mod apps;
mod device;
mod files;
mod input;
mod media;
mod screen;

pub fn tool_schemas() -> Value {
    let mut schemas = Vec::new();
    schemas.extend(device::system());
    schemas.extend(screen::mirror());
    schemas.extend(apps::cache());
    schemas.extend(input::gamepad());
    schemas.extend(screen::preview());
    schemas.extend(media::controls());
    schemas.extend(files::storage());
    schemas.extend(apps::management());
    schemas.extend(media::publications());
    schemas.extend(files::operations());
    schemas.extend(input::remote());
    schemas.extend(apps::launcher());
    schemas.extend(device::screenshot());
    schemas.extend(media::status());
    schemas.extend(device::information());
    Value::Array(schemas)
}
