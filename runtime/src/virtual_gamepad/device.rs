use super::state::{delta, State, AXES, BUTTONS};
use anyhow::{bail, Context, Result};
use std::{
    ffi::CString,
    fs::{File, OpenOptions},
    io::Write,
    os::unix::{
        fs::{FileTypeExt, OpenOptionsExt},
        io::AsRawFd,
    },
};
// Linux UAPI ioctl encodings; structs explicitly match input/uinput ABI (including ARM32).
const GET_VERSION: u32 = 0x8004552d;
const DEV_SETUP: u32 = 0x405c5503;
const ABS_SETUP: u32 = 0x401c5504;
#[repr(C)]
struct Setup {
    id: [u16; 4],
    name: [u8; 80],
    ff_effects: u32,
}
#[repr(C)]
struct AbsSetup {
    code: u16,
    padding: u16,
    info: [i32; 6],
}
#[repr(C)]
struct Event {
    time: libc::timeval,
    type_: u16,
    code: u16,
    value: i32,
}
fn ioctl(fd: &File, request: u32, arg: usize) -> Result<()> {
    if unsafe { libc::ioctl(fd.as_raw_fd(), request as _, arg) } < 0 {
        bail!("uinput ioctl failed: {}", std::io::Error::last_os_error());
    }
    Ok(())
}
pub(super) fn open() -> Result<(File, u32)> {
    if unsafe { libc::geteuid() } != 0 {
        bail!("virtual gamepad requires a verified root runtime");
    }
    let file = OpenOptions::new()
        .write(true)
        .custom_flags(libc::O_NONBLOCK | libc::O_CLOEXEC | libc::O_NOFOLLOW)
        .open("/dev/uinput")
        .context("uinput unavailable or permission denied")?;
    if !file.metadata()?.file_type().is_char_device() {
        bail!("uinput is not a character device");
    }
    let mut version = 0u32;
    ioctl(&file, GET_VERSION, &mut version as *mut _ as usize)?;
    if version < 5 {
        bail!("uinput version 5 or newer required");
    }
    Ok((file, version))
}
pub(super) struct Device {
    file: File,
    created: bool,
    pub state: State,
}
impl Device {
    pub fn create(name: &str) -> Result<Self> {
        let (file, _) = open()?;
        let mut device = Self {
            file,
            created: false,
            state: State::default(),
        };
        ioctl(&device.file, 0x40045564, 1)?;
        ioctl(&device.file, 0x40045564, 3)?;
        for (_, code) in BUTTONS {
            ioctl(&device.file, 0x40045565, *code as usize)?;
        }
        for code in AXES {
            ioctl(&device.file, 0x40045567, *code as usize)?;
            let hat = *code >= 16;
            let value = AbsSetup {
                code: *code,
                padding: 0,
                info: if hat {
                    [0, -1, 1, 0, 0, 0]
                } else {
                    [0, -32767, 32767, 0, 2048, 0]
                },
            };
            ioctl(&device.file, ABS_SETUP, &value as *const _ as usize)?;
        }
        let mut setup = Setup {
            id: [6, 0, 0, 1],
            name: [0; 80],
            ff_effects: 0,
        };
        let bytes = name.as_bytes();
        if bytes.len() > 79 {
            bail!("virtual device name too long");
        }
        setup.name[..bytes.len()].copy_from_slice(bytes);
        // InputReader groups devices with the same physical location. A unique
        // location keeps a rapidly reopened lease from inheriting the retired
        // gamepad's logical identity and cached name.
        let physical = CString::new(name).context("virtual device name contains NUL")?;
        let set_phys = 0x4000556c | ((std::mem::size_of::<*const libc::c_char>() as u32) << 16);
        ioctl(&device.file, set_phys, physical.as_ptr() as usize)?;
        ioctl(&device.file, DEV_SETUP, &setup as *const _ as usize)?;
        ioctl(&device.file, 0x5501, 0)?;
        device.created = true;
        Ok(device)
    }
    pub fn set(&mut self, next: State) -> Result<()> {
        let events = delta(&self.state, &next);
        for (type_, code, value) in events {
            let event = Event {
                time: libc::timeval {
                    tv_sec: 0,
                    tv_usec: 0,
                },
                type_,
                code,
                value,
            };
            let bytes = unsafe {
                std::slice::from_raw_parts(
                    &event as *const _ as *const u8,
                    std::mem::size_of::<Event>(),
                )
            };
            self.file.write_all(bytes)?;
        }
        self.state = next;
        Ok(())
    }
}
impl Drop for Device {
    fn drop(&mut self) {
        if self.created {
            let _ = self.set(State::default());
            let _ = ioctl(&self.file, 0x5502, 0);
        }
    }
}
#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn uapi_layouts_match_the_kernel_contract() {
        assert_eq!(std::mem::size_of::<Setup>(), 92);
        assert_eq!(std::mem::size_of::<AbsSetup>(), 28);
        assert_eq!(
            std::mem::size_of::<Event>(),
            std::mem::size_of::<libc::timeval>() + 8
        );
    }
}
