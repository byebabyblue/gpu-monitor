from __future__ import annotations

import json
import os
import shutil
import subprocess
from datetime import datetime
from pathlib import Path
import tkinter as tk
from tkinter import filedialog, messagebox, ttk

from PIL import Image, ImageChops, ImageDraw, ImageFilter, ImageTk


PROJECT_ROOT = Path(__file__).resolve().parents[1]
DEFAULT_SOURCE = PROJECT_ROOT / "tools" / "assets" / "snake-icon-source.png"
TARGET_PNG = PROJECT_ROOT / "kyant_frontend" / "src" / "main" / "resources" / "app-icon.png"
TARGET_ICO = PROJECT_ROOT / "kyant_frontend" / "src" / "main" / "resources" / "app-icon.ico"
APP_DATA_ROOT = Path(os.environ.get("APPDATA", Path.home())) / "GPU Monitor"
SETTINGS_PATH = APP_DATA_ROOT / "icon-crop-tool.json"
BACKUP_ROOT = APP_DATA_ROOT / "Icon Backups"
ICO_SIZES = [16, 20, 24, 32, 40, 48, 64, 128, 256]
OUTPUT_SIZE = 1024
MIN_CROP_SIZE = 64.0


def clamp(value: float, minimum: float, maximum: float) -> float:
    return max(minimum, min(value, maximum))


def remove_connected_outer_white(image: Image.Image) -> Image.Image:
    """Remove only bright pixels connected to the outer image boundary."""
    rgb = image.convert("RGB")
    red, green, blue = rgb.split()
    red_mask = red.point(lambda value: 255 if value > 70 else 0)
    green_mask = green.point(lambda value: 255 if value > 180 else 0)
    blue_mask = blue.point(lambda value: 255 if value > 180 else 0)
    candidate = ImageChops.multiply(ImageChops.multiply(red_mask, green_mask), blue_mask)

    flood_map = candidate.point(lambda value: 0 if value else 255)
    ImageDraw.floodfill(flood_map, (0, 0), 128, thresh=0)
    outside = flood_map.point(lambda value: 255 if value == 128 else 0)
    alpha = ImageChops.invert(outside).filter(ImageFilter.GaussianBlur(0.8))

    edge_mask = alpha.point(lambda value: 255 if value < 255 else 0)
    cleaned_rgb = Image.composite(
        Image.new("RGB", rgb.size, (18, 200, 209)),
        rgb,
        edge_mask,
    )
    result = cleaned_rgb.convert("RGBA")
    result.putalpha(alpha)
    return result


def checkerboard(size: tuple[int, int], tile: int = 14) -> Image.Image:
    image = Image.new("RGBA", size, (26, 31, 43, 255))
    draw = ImageDraw.Draw(image)
    colors = ((39, 46, 61, 255), (29, 35, 48, 255))
    for y in range(0, size[1], tile):
        for x in range(0, size[0], tile):
            draw.rectangle(
                (x, y, min(x + tile, size[0]), min(y + tile, size[1])),
                fill=colors[((x // tile) + (y // tile)) % 2],
            )
    return image


class IconCropTool:
    def __init__(self, root: tk.Tk) -> None:
        self.root = root
        self.root.title("GPU Monitor 图标裁剪与圆角调整")
        self.root.geometry("1160x760")
        self.root.minsize(1000, 680)
        self.root.configure(background="#101522")

        self.source_path = DEFAULT_SOURCE
        self.source_image = Image.new("RGBA", (1024, 1024), (0, 0, 0, 0))
        self.transparent_source = self.source_image
        self.crop_x = 0.0
        self.crop_y = 0.0
        self.crop_size = 1024.0
        self.radius_percent = tk.IntVar(value=15)
        self.remove_white = tk.BooleanVar(value=True)
        self.radius_text = tk.StringVar(value="15%")
        self.crop_text = tk.StringVar(value="")
        self.status_text = tk.StringVar(value="拖动裁剪框，满意后保存或应用。")

        self.display_scale = 1.0
        self.display_offset_x = 0.0
        self.display_offset_y = 0.0
        self.editor_photo: ImageTk.PhotoImage | None = None
        self.preview_photo: ImageTk.PhotoImage | None = None
        self.drag_mode: str | None = None
        self.drag_handle: str | None = None
        self.drag_start_canvas = (0.0, 0.0)
        self.drag_start_crop = (0.0, 0.0, 0.0)

        self._configure_styles()
        self._build_ui()
        restored = self._load_saved_state()
        self._load_image(self.source_path, keep_crop=restored)
        if not restored:
            self.reset_recommended_crop()
        self.root.protocol("WM_DELETE_WINDOW", self.close)
        self.root.after(80, self.redraw)

    def _configure_styles(self) -> None:
        style = ttk.Style(self.root)
        if "clam" in style.theme_names():
            style.theme_use("clam")
        style.configure("App.TFrame", background="#101522")
        style.configure("Panel.TFrame", background="#171e2d")
        style.configure("Title.TLabel", background="#101522", foreground="#f4f7ff", font=("Segoe UI", 18, "bold"))
        style.configure("Text.TLabel", background="#171e2d", foreground="#e6edf8", font=("Segoe UI", 10))
        style.configure("Muted.TLabel", background="#171e2d", foreground="#9baac0", font=("Segoe UI", 9))
        style.configure("Value.TLabel", background="#171e2d", foreground="#73e6ef", font=("Segoe UI", 11, "bold"))
        style.configure("App.TButton", font=("Segoe UI", 10), padding=(12, 8))
        style.configure("Primary.TButton", font=("Segoe UI", 10, "bold"), padding=(12, 9), foreground="#062229", background="#54dce6")
        style.map("Primary.TButton", background=[("active", "#79e9ef")])
        style.configure("App.Horizontal.TScale", background="#171e2d", troughcolor="#293448")
        style.configure("App.TCheckbutton", background="#171e2d", foreground="#dce7f5", font=("Segoe UI", 9))
        style.map("App.TCheckbutton", background=[("active", "#171e2d")])

    def _build_ui(self) -> None:
        shell = ttk.Frame(self.root, style="App.TFrame", padding=18)
        shell.pack(fill="both", expand=True)

        header = ttk.Frame(shell, style="App.TFrame")
        header.pack(fill="x", pady=(0, 14))
        ttk.Label(header, text="图标裁剪与圆角调整", style="Title.TLabel").pack(side="left")
        ttk.Button(header, text="打开图片", command=self.open_image, style="App.TButton").pack(side="right")

        body = ttk.Frame(shell, style="App.TFrame")
        body.pack(fill="both", expand=True)
        body.columnconfigure(0, weight=1)
        body.columnconfigure(1, weight=0)
        body.rowconfigure(0, weight=1)

        editor_panel = ttk.Frame(body, style="Panel.TFrame", padding=10)
        editor_panel.grid(row=0, column=0, sticky="nsew", padx=(0, 14))
        editor_panel.rowconfigure(0, weight=1)
        editor_panel.columnconfigure(0, weight=1)
        self.editor = tk.Canvas(
            editor_panel,
            background="#0b101b",
            highlightthickness=0,
            cursor="crosshair",
        )
        self.editor.grid(row=0, column=0, sticky="nsew")
        self.editor.bind("<Configure>", lambda _event: self.redraw())
        self.editor.bind("<ButtonPress-1>", self.on_press)
        self.editor.bind("<B1-Motion>", self.on_drag)
        self.editor.bind("<ButtonRelease-1>", self.on_release)
        self.editor.bind("<Motion>", self.on_hover)
        self.editor.bind("<MouseWheel>", self.on_mouse_wheel)
        self.editor.bind("<Left>", lambda _event: self.nudge(-1, 0))
        self.editor.bind("<Right>", lambda _event: self.nudge(1, 0))
        self.editor.bind("<Up>", lambda _event: self.nudge(0, -1))
        self.editor.bind("<Down>", lambda _event: self.nudge(0, 1))
        ttk.Label(
            editor_panel,
            text="框内拖动可移动 · 拖四个角可缩放 · 滚轮微调大小 · 方向键微调位置",
            style="Muted.TLabel",
        ).grid(row=1, column=0, sticky="w", pady=(9, 0))

        controls = ttk.Frame(body, style="Panel.TFrame", width=330, padding=18)
        controls.grid(row=0, column=1, sticky="ns")
        controls.grid_propagate(False)
        controls.columnconfigure(0, weight=1)

        ttk.Label(controls, text="输出预览", style="Text.TLabel").grid(row=0, column=0, sticky="w")
        self.preview = tk.Canvas(
            controls,
            width=292,
            height=292,
            background="#111725",
            highlightthickness=0,
        )
        self.preview.grid(row=1, column=0, pady=(8, 16))

        radius_row = ttk.Frame(controls, style="Panel.TFrame")
        radius_row.grid(row=2, column=0, sticky="ew")
        radius_row.columnconfigure(0, weight=1)
        ttk.Label(radius_row, text="圆角曲率", style="Text.TLabel").grid(row=0, column=0, sticky="w")
        ttk.Label(radius_row, textvariable=self.radius_text, style="Value.TLabel").grid(row=0, column=1, sticky="e")
        self.radius_slider = ttk.Scale(
            controls,
            from_=0,
            to=50,
            variable=self.radius_percent,
            command=self.on_radius_changed,
            style="App.Horizontal.TScale",
        )
        self.radius_slider.grid(row=3, column=0, sticky="ew", pady=(8, 2))
        ttk.Label(controls, text="0% 为直角，50% 为圆形", style="Muted.TLabel").grid(row=4, column=0, sticky="w")

        ttk.Checkbutton(
            controls,
            text="自动去除与外边缘相连的白色",
            variable=self.remove_white,
            command=self.on_remove_white_changed,
            style="App.TCheckbutton",
        ).grid(row=5, column=0, sticky="w", pady=(16, 8))

        ttk.Label(controls, textvariable=self.crop_text, style="Muted.TLabel", wraplength=292).grid(row=6, column=0, sticky="w", pady=(0, 14))

        button_row = ttk.Frame(controls, style="Panel.TFrame")
        button_row.grid(row=7, column=0, sticky="ew")
        button_row.columnconfigure(0, weight=1)
        button_row.columnconfigure(1, weight=1)
        ttk.Button(button_row, text="完整画面", command=self.reset_full_crop, style="App.TButton").grid(row=0, column=0, sticky="ew", padx=(0, 5))
        ttk.Button(button_row, text="推荐取景", command=self.reset_recommended_crop, style="App.TButton").grid(row=0, column=1, sticky="ew", padx=(5, 0))

        ttk.Button(controls, text="保存 PNG…", command=self.save_png, style="App.TButton").grid(row=8, column=0, sticky="ew", pady=(12, 8))
        ttk.Button(controls, text="应用到 GPU Monitor", command=self.apply_to_project, style="Primary.TButton").grid(row=9, column=0, sticky="ew")
        ttk.Label(
            controls,
            textvariable=self.status_text,
            style="Muted.TLabel",
            wraplength=292,
            justify="left",
        ).grid(row=10, column=0, sticky="sw", pady=(16, 0))
        controls.rowconfigure(10, weight=1)

    def _load_saved_state(self) -> bool:
        if not SETTINGS_PATH.is_file():
            return False
        try:
            settings = json.loads(SETTINGS_PATH.read_text(encoding="utf-8"))
            saved_source = Path(settings.get("source", ""))
            if saved_source.is_file():
                self.source_path = saved_source
            self.crop_x = float(settings.get("crop_x", self.crop_x))
            self.crop_y = float(settings.get("crop_y", self.crop_y))
            self.crop_size = float(settings.get("crop_size", self.crop_size))
            self.radius_percent.set(int(settings.get("radius_percent", 15)))
            self.remove_white.set(bool(settings.get("remove_white", True)))
            return True
        except (OSError, ValueError, TypeError, json.JSONDecodeError):
            self.source_path = DEFAULT_SOURCE
            return False

    def _save_state(self) -> None:
        try:
            SETTINGS_PATH.parent.mkdir(parents=True, exist_ok=True)
            payload = {
                "source": str(self.source_path),
                "crop_x": round(self.crop_x, 3),
                "crop_y": round(self.crop_y, 3),
                "crop_size": round(self.crop_size, 3),
                "radius_percent": int(round(self.radius_percent.get())),
                "remove_white": bool(self.remove_white.get()),
            }
            SETTINGS_PATH.write_text(json.dumps(payload, ensure_ascii=False, indent=2), encoding="utf-8")
        except OSError:
            pass

    def _load_image(self, path: Path, keep_crop: bool = False) -> None:
        try:
            image = Image.open(path).convert("RGBA")
        except (OSError, ValueError) as error:
            messagebox.showerror("无法打开图片", str(error), parent=self.root)
            return

        self.source_path = path
        self.source_image = image
        self.transparent_source = remove_connected_outer_white(image)
        if keep_crop:
            self._clamp_crop()
        else:
            self.reset_full_crop(redraw=False)
        self._set_window_icon()
        self.status_text.set(f"已载入：{path.name}")
        self.redraw()

    def _set_window_icon(self) -> None:
        try:
            icon = self.transparent_source.resize((64, 64), Image.Resampling.LANCZOS)
            self.window_icon = ImageTk.PhotoImage(icon)
            self.root.iconphoto(True, self.window_icon)
        except (OSError, tk.TclError):
            pass

    def open_image(self) -> None:
        selected = filedialog.askopenfilename(
            parent=self.root,
            title="选择图标原图",
            filetypes=[("图片", "*.png *.jpg *.jpeg *.webp *.bmp"), ("所有文件", "*.*")],
        )
        if selected:
            self._load_image(Path(selected), keep_crop=False)

    def reset_full_crop(self, redraw: bool = True) -> None:
        size = float(min(self.source_image.size))
        self.crop_size = size
        self.crop_x = (self.source_image.width - size) / 2.0
        self.crop_y = (self.source_image.height - size) / 2.0
        if redraw:
            self.redraw()

    def reset_recommended_crop(self) -> None:
        width, height = self.source_image.size
        inset = min(width, height) * 0.0375
        self.crop_x = inset
        self.crop_y = inset
        self.crop_size = min(width - self.crop_x, height - self.crop_y)
        self.radius_percent.set(15)
        self.redraw()

    def _clamp_crop(self) -> None:
        width, height = self.source_image.size
        maximum = float(min(width, height))
        self.crop_size = clamp(self.crop_size, MIN_CROP_SIZE, maximum)
        self.crop_x = clamp(self.crop_x, 0.0, width - self.crop_size)
        self.crop_y = clamp(self.crop_y, 0.0, height - self.crop_size)

    def redraw(self) -> None:
        self._clamp_crop()
        self._draw_editor()
        self._draw_preview()
        self.radius_text.set(f"{int(round(self.radius_percent.get()))}%")
        self.crop_text.set(
            f"原图 {self.source_image.width} × {self.source_image.height}\n"
            f"裁剪框 x={int(round(self.crop_x))}，y={int(round(self.crop_y))}，"
            f"边长={int(round(self.crop_size))}"
        )

    def _draw_editor(self) -> None:
        self.editor.delete("all")
        canvas_width = max(1, self.editor.winfo_width())
        canvas_height = max(1, self.editor.winfo_height())
        padding = 24
        image_width, image_height = self.source_image.size
        self.display_scale = min(
            max(1, canvas_width - padding * 2) / image_width,
            max(1, canvas_height - padding * 2) / image_height,
        )
        display_width = max(1, int(round(image_width * self.display_scale)))
        display_height = max(1, int(round(image_height * self.display_scale)))
        self.display_offset_x = (canvas_width - display_width) / 2.0
        self.display_offset_y = (canvas_height - display_height) / 2.0

        shown_source = self.transparent_source if self.remove_white.get() else self.source_image
        display = checkerboard((display_width, display_height), tile=12)
        resized = shown_source.resize((display_width, display_height), Image.Resampling.LANCZOS)
        display.alpha_composite(resized)
        self.editor_photo = ImageTk.PhotoImage(display)
        self.editor.create_image(
            self.display_offset_x,
            self.display_offset_y,
            image=self.editor_photo,
            anchor="nw",
        )

        left, top, right, bottom = self._crop_canvas_bounds()
        image_left = self.display_offset_x
        image_top = self.display_offset_y
        image_right = image_left + display_width
        image_bottom = image_top + display_height
        shade = {"fill": "#05070c", "stipple": "gray50", "outline": ""}
        self.editor.create_rectangle(image_left, image_top, image_right, top, **shade)
        self.editor.create_rectangle(image_left, bottom, image_right, image_bottom, **shade)
        self.editor.create_rectangle(image_left, top, left, bottom, **shade)
        self.editor.create_rectangle(right, top, image_right, bottom, **shade)

        self.editor.create_rectangle(left, top, right, bottom, outline="#78e6ef", width=3)
        radius = (right - left) * clamp(self.radius_percent.get(), 0, 50) / 100.0
        self._draw_rounded_outline(left, top, right, bottom, radius)
        for _name, (x, y) in self._handle_positions().items():
            self.editor.create_rectangle(x - 6, y - 6, x + 6, y + 6, fill="#f3fbff", outline="#2fcbd8", width=2)

    def _draw_rounded_outline(self, left: float, top: float, right: float, bottom: float, radius: float) -> None:
        if radius <= 1:
            return
        color = "#c9fbff"
        width = 2
        self.editor.create_line(left + radius, top, right - radius, top, fill=color, width=width)
        self.editor.create_line(left + radius, bottom, right - radius, bottom, fill=color, width=width)
        self.editor.create_line(left, top + radius, left, bottom - radius, fill=color, width=width)
        self.editor.create_line(right, top + radius, right, bottom - radius, fill=color, width=width)
        diameter = radius * 2
        self.editor.create_arc(left, top, left + diameter, top + diameter, start=90, extent=90, style="arc", outline=color, width=width)
        self.editor.create_arc(right - diameter, top, right, top + diameter, start=0, extent=90, style="arc", outline=color, width=width)
        self.editor.create_arc(left, bottom - diameter, left + diameter, bottom, start=180, extent=90, style="arc", outline=color, width=width)
        self.editor.create_arc(right - diameter, bottom - diameter, right, bottom, start=270, extent=90, style="arc", outline=color, width=width)

    def _draw_preview(self) -> None:
        preview_size = 276
        rendered = self.render_output(preview_size)
        background = checkerboard((292, 292), tile=12)
        background.alpha_composite(rendered, ((292 - preview_size) // 2, (292 - preview_size) // 2))
        self.preview_photo = ImageTk.PhotoImage(background)
        self.preview.delete("all")
        self.preview.create_image(0, 0, image=self.preview_photo, anchor="nw")

    def _crop_canvas_bounds(self) -> tuple[float, float, float, float]:
        left = self.display_offset_x + self.crop_x * self.display_scale
        top = self.display_offset_y + self.crop_y * self.display_scale
        size = self.crop_size * self.display_scale
        return left, top, left + size, top + size

    def _handle_positions(self) -> dict[str, tuple[float, float]]:
        left, top, right, bottom = self._crop_canvas_bounds()
        return {
            "nw": (left, top),
            "ne": (right, top),
            "sw": (left, bottom),
            "se": (right, bottom),
        }

    def _canvas_to_image(self, x: float, y: float) -> tuple[float, float]:
        return (
            (x - self.display_offset_x) / max(self.display_scale, 1e-6),
            (y - self.display_offset_y) / max(self.display_scale, 1e-6),
        )

    def _hit_handle(self, x: float, y: float) -> str | None:
        for name, (handle_x, handle_y) in self._handle_positions().items():
            if abs(x - handle_x) <= 12 and abs(y - handle_y) <= 12:
                return name
        return None

    def on_press(self, event: tk.Event) -> None:
        self.editor.focus_set()
        handle = self._hit_handle(event.x, event.y)
        left, top, right, bottom = self._crop_canvas_bounds()
        if handle is not None:
            self.drag_mode = "resize"
            self.drag_handle = handle
        elif left <= event.x <= right and top <= event.y <= bottom:
            self.drag_mode = "move"
            self.drag_handle = None
        else:
            self.drag_mode = None
            return
        self.drag_start_canvas = (event.x, event.y)
        self.drag_start_crop = (self.crop_x, self.crop_y, self.crop_size)

    def on_drag(self, event: tk.Event) -> None:
        if self.drag_mode == "move":
            delta_x = (event.x - self.drag_start_canvas[0]) / max(self.display_scale, 1e-6)
            delta_y = (event.y - self.drag_start_canvas[1]) / max(self.display_scale, 1e-6)
            self.crop_x = self.drag_start_crop[0] + delta_x
            self.crop_y = self.drag_start_crop[1] + delta_y
        elif self.drag_mode == "resize" and self.drag_handle:
            image_x, image_y = self._canvas_to_image(event.x, event.y)
            self._resize_from_handle(self.drag_handle, image_x, image_y)
        else:
            return
        self._clamp_crop()
        self.redraw()

    def _resize_from_handle(self, handle: str, image_x: float, image_y: float) -> None:
        start_x, start_y, start_size = self.drag_start_crop
        if handle == "nw":
            fixed_x, fixed_y, sign_x, sign_y = start_x + start_size, start_y + start_size, -1, -1
        elif handle == "ne":
            fixed_x, fixed_y, sign_x, sign_y = start_x, start_y + start_size, 1, -1
        elif handle == "sw":
            fixed_x, fixed_y, sign_x, sign_y = start_x + start_size, start_y, -1, 1
        else:
            fixed_x, fixed_y, sign_x, sign_y = start_x, start_y, 1, 1

        requested = max(abs(image_x - fixed_x), abs(image_y - fixed_y))
        width, height = self.source_image.size
        max_x = fixed_x if sign_x < 0 else width - fixed_x
        max_y = fixed_y if sign_y < 0 else height - fixed_y
        size = clamp(requested, MIN_CROP_SIZE, min(max_x, max_y))
        self.crop_size = size
        self.crop_x = fixed_x - size if sign_x < 0 else fixed_x
        self.crop_y = fixed_y - size if sign_y < 0 else fixed_y

    def on_release(self, _event: tk.Event) -> None:
        self.drag_mode = None
        self.drag_handle = None
        self._save_state()

    def on_hover(self, event: tk.Event) -> None:
        handle = self._hit_handle(event.x, event.y)
        if handle in {"nw", "se"}:
            cursor = "size_nw_se"
        elif handle in {"ne", "sw"}:
            cursor = "size_ne_sw"
        else:
            left, top, right, bottom = self._crop_canvas_bounds()
            cursor = "fleur" if left <= event.x <= right and top <= event.y <= bottom else "crosshair"
        self.editor.configure(cursor=cursor)

    def on_mouse_wheel(self, event: tk.Event) -> None:
        factor = 0.96 if event.delta > 0 else 1.04
        width, height = self.source_image.size
        new_size = clamp(self.crop_size * factor, MIN_CROP_SIZE, min(width, height))
        center_x = self.crop_x + self.crop_size / 2.0
        center_y = self.crop_y + self.crop_size / 2.0
        self.crop_size = new_size
        self.crop_x = center_x - new_size / 2.0
        self.crop_y = center_y - new_size / 2.0
        self._clamp_crop()
        self.redraw()

    def nudge(self, delta_x: int, delta_y: int) -> str:
        self.crop_x += delta_x
        self.crop_y += delta_y
        self._clamp_crop()
        self.redraw()
        return "break"

    def on_radius_changed(self, _value: str | None = None) -> None:
        self.radius_percent.set(int(round(self.radius_percent.get())))
        self.redraw()

    def on_remove_white_changed(self) -> None:
        self.redraw()

    def render_output(self, output_size: int = OUTPUT_SIZE) -> Image.Image:
        source = self.transparent_source if self.remove_white.get() else self.source_image
        left = int(round(self.crop_x))
        top = int(round(self.crop_y))
        size = max(1, int(round(self.crop_size)))
        right = min(source.width, left + size)
        bottom = min(source.height, top + size)
        left = max(0, right - size)
        top = max(0, bottom - size)
        cropped = source.crop((left, top, right, bottom)).resize(
            (output_size, output_size),
            Image.Resampling.LANCZOS,
        )
        radius = int(round(output_size * clamp(self.radius_percent.get(), 0, 50) / 100.0))
        rounded_mask = Image.new("L", (output_size, output_size), 0)
        ImageDraw.Draw(rounded_mask).rounded_rectangle(
            (0, 0, output_size - 1, output_size - 1),
            radius=radius,
            fill=255,
        )
        alpha = ImageChops.multiply(cropped.getchannel("A"), rounded_mask)
        result = cropped.copy()
        result.putalpha(alpha)
        return result

    def save_png(self) -> None:
        selected = filedialog.asksaveasfilename(
            parent=self.root,
            title="保存透明 PNG",
            defaultextension=".png",
            initialfile="gpu-monitor-icon.png",
            filetypes=[("PNG 图片", "*.png")],
        )
        if not selected:
            return
        try:
            self.render_output().save(selected, format="PNG", optimize=True)
            self._save_state()
            self.status_text.set(f"已保存：{selected}")
        except OSError as error:
            messagebox.showerror("保存失败", str(error), parent=self.root)

    def apply_to_project(self) -> None:
        try:
            icon = self.render_output()
            timestamp = datetime.now().strftime("%Y%m%d-%H%M%S-%f")
            backup_dir = BACKUP_ROOT / timestamp
            backup_dir.mkdir(parents=True, exist_ok=False)
            for target in (TARGET_PNG, TARGET_ICO):
                if target.is_file():
                    shutil.copy2(target, backup_dir / target.name)

            TARGET_PNG.parent.mkdir(parents=True, exist_ok=True)
            png_temp = TARGET_PNG.with_name(TARGET_PNG.name + ".tmp")
            ico_temp = TARGET_ICO.with_name(TARGET_ICO.name + ".tmp")
            icon.save(png_temp, format="PNG", optimize=True)
            icon.save(
                ico_temp,
                format="ICO",
                sizes=[(size, size) for size in ICO_SIZES],
            )
            os.replace(png_temp, TARGET_PNG)
            os.replace(ico_temp, TARGET_ICO)
            self._save_state()
            self._refresh_windows_icons()
            self.status_text.set(
                f"已应用到项目，旧图标备份在：{backup_dir}\n"
                "主程序需要重新构建后才会显示最终效果。"
            )
            messagebox.showinfo(
                "应用成功",
                "图标资源已经保存。\n\n返回 Codex 后说“重新打开预览”即可查看主程序效果。",
                parent=self.root,
            )
        except (OSError, ValueError) as error:
            messagebox.showerror("应用失败", str(error), parent=self.root)

    @staticmethod
    def _refresh_windows_icons() -> None:
        executable = Path(os.environ.get("SystemRoot", r"C:\Windows")) / "System32" / "ie4uinit.exe"
        if not executable.is_file():
            return
        creation_flags = getattr(subprocess, "CREATE_NO_WINDOW", 0)
        try:
            subprocess.run(
                [str(executable), "-show"],
                check=False,
                stdout=subprocess.DEVNULL,
                stderr=subprocess.DEVNULL,
                creationflags=creation_flags,
            )
        except OSError:
            pass

    def close(self) -> None:
        self._save_state()
        self.root.destroy()


def enable_windows_dpi_awareness() -> None:
    if os.name != "nt":
        return
    try:
        import ctypes

        ctypes.windll.shcore.SetProcessDpiAwareness(1)
    except (AttributeError, OSError):
        pass


def main() -> None:
    enable_windows_dpi_awareness()
    root = tk.Tk()
    IconCropTool(root)
    root.mainloop()


if __name__ == "__main__":
    main()
