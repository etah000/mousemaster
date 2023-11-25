package jmouseable.jmouseable;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.*;
import jmouseable.jmouseable.WindowsMouse.CursorPositionAndSize;

import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

public class WindowsOverlay {

    private static final int indicatorEdgeThreshold = 100; // in pixels
    private static final int indicatorSize = 16;

    private static WinDef.HWND indicatorWindowHwnd;
    private static Set<AttachGridWindow> attachGridWindows;
    private static boolean showingIndicator;
    private static String currentIndicatorHexColor;
    private static boolean showingAttachGrid;
    private static Attach currentAttach;
    private static WinDef.POINT mousePosition;

    private record AttachGridWindow(Monitor monitor, WinDef.HWND hwnd) {

    }

    private static int bestIndicatorX(int mouseX, int cursorWidth, int monitorLeft,
                                      int monitorRight) {
        mouseX = Math.min(monitorRight, Math.max(monitorLeft, mouseX));
        boolean isNearLeftEdge = mouseX <= (monitorLeft + indicatorEdgeThreshold);
        boolean isNearRightEdge = mouseX >= (monitorRight - indicatorEdgeThreshold);
        if (isNearRightEdge)
            return mouseX - indicatorSize;
        return mouseX + cursorWidth / 2;
    }

    private static int bestIndicatorY(int mouseY, int cursorHeight, int monitorTop,
                                      int monitorBottom) {
        mouseY = Math.min(monitorBottom, Math.max(monitorTop, mouseY));
        boolean isNearBottomEdge = mouseY >= (monitorBottom - indicatorEdgeThreshold);
        boolean isNearTopEdge = mouseY <= (monitorTop + indicatorEdgeThreshold);
        if (isNearBottomEdge)
            return mouseY - indicatorSize;
        return mouseY + cursorHeight / 2;
    }

    private static void createIndicatorWindow() {
        CursorPositionAndSize cursorPositionAndSize =
                WindowsMouse.cursorPositionAndSize();
        WinUser.MONITORINFO monitorInfo =
                findCurrentMonitorPosition(cursorPositionAndSize.position());
        indicatorWindowHwnd = createWindow("Indicator",
                bestIndicatorX(cursorPositionAndSize.position().x,
                        cursorPositionAndSize.width(), monitorInfo.rcMonitor.left,
                        monitorInfo.rcMonitor.right),
                bestIndicatorY(cursorPositionAndSize.position().y,
                        cursorPositionAndSize.height(), monitorInfo.rcMonitor.top,
                        monitorInfo.rcMonitor.bottom), indicatorSize, indicatorSize,
                WindowsOverlay::indicatorWindowCallback);
    }

    private record Monitor(int top, int bottom, int left, int right) {

    }

    private static void createAttachGridWindows() {
        attachGridWindows = new HashSet<>();
        for (Monitor monitor : findMonitors()) {
            WinDef.HWND hwnd = createWindow("AttachGrid",
                    monitor.left, monitor.top,
                    monitor.right() - monitor.left(), monitor.bottom() - monitor.top(),
                    WindowsOverlay::attachGridWindowCallback);
            attachGridWindows.add(new AttachGridWindow(monitor, hwnd));
        }
    }

    private static Set<Monitor> findMonitors() {
        Set<Monitor> monitors = new HashSet<>();
        User32.INSTANCE.EnumDisplayMonitors(null, null, new WinUser.MONITORENUMPROC() {
            @Override
            public int apply(WinUser.HMONITOR hMonitor, WinDef.HDC hdcMonitor,
                             WinDef.RECT lprcMonitor, WinDef.LPARAM dwData) {
                monitors.add(
                        new Monitor(lprcMonitor.top, lprcMonitor.bottom, lprcMonitor.left,
                                lprcMonitor.right));
                return 1;
            }
        }, null);
        return monitors;
    }

    private static WinDef.HWND createWindow(String windowName, int windowX, int windowY,
                                            int windowWidth, int windowHeight,
                                            WinUser.WindowProc windowCallback) {
        WinUser.WNDCLASSEX wClass = new WinUser.WNDCLASSEX();
        wClass.hbrBackground = null;
        wClass.lpszClassName = "JMouseable" + windowName + "ClassName";
        wClass.lpfnWndProc = windowCallback;
        WinDef.ATOM registerClassExResult = User32.INSTANCE.RegisterClassEx(wClass);
        WinDef.HWND hwnd = User32.INSTANCE.CreateWindowEx(
                User32.WS_EX_TOPMOST | ExtendedUser32.WS_EX_TOOLWINDOW | ExtendedUser32.WS_EX_NOACTIVATE
                | ExtendedUser32.WS_EX_LAYERED | ExtendedUser32.WS_EX_TRANSPARENT,
                wClass.lpszClassName, "JMouseable" + windowName + "WindowName",
                WinUser.WS_POPUP, windowX, windowY, windowWidth, windowHeight, null, null,
                wClass.hInstance, null);
        User32.INSTANCE.SetLayeredWindowAttributes(hwnd, 0, (byte) 0,
                WinUser.LWA_COLORKEY);
        return hwnd;
    }

    private static void removeWindow(WinDef.HWND hwnd) {
        User32.INSTANCE.DestroyWindow(hwnd);
    }

    private static WinDef.LRESULT indicatorWindowCallback(WinDef.HWND hwnd, int uMsg,
                                                          WinDef.WPARAM wParam,
                                                          WinDef.LPARAM lParam) {
        switch (uMsg) {
            case WinUser.WM_PAINT:
                ExtendedUser32.PAINTSTRUCT ps = new ExtendedUser32.PAINTSTRUCT();
                WinDef.HBRUSH hbrBackground = ExtendedGDI32.INSTANCE.CreateSolidBrush(
                        hexColorStringToInt(currentIndicatorHexColor));
                WinDef.HDC hdc = ExtendedUser32.INSTANCE.BeginPaint(hwnd, ps);
                ExtendedUser32.INSTANCE.FillRect(hdc, ps.rcPaint, hbrBackground);
                GDI32.INSTANCE.DeleteObject(hbrBackground);
                ExtendedUser32.INSTANCE.EndPaint(hwnd, ps);
                break;
        }
        return User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam);
    }

    private static WinDef.LRESULT attachGridWindowCallback(WinDef.HWND hwnd, int uMsg,
                                                           WinDef.WPARAM wParam,
                                                           WinDef.LPARAM lParam) {
        switch (uMsg) {
            case WinUser.WM_PAINT:
                ExtendedUser32.PAINTSTRUCT ps = new ExtendedUser32.PAINTSTRUCT();
                WinDef.HDC hdc = ExtendedUser32.INSTANCE.BeginPaint(hwnd, ps);
                // If not cleared, the previous drawings will be painted.
                clearWindow(hdc, ps);
                paintGrid(hdc, ps);
                ExtendedUser32.INSTANCE.EndPaint(hwnd, ps);
                break;
        }
        return User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam);
    }

    private static void clearWindow(WinDef.HDC hdc, ExtendedUser32.PAINTSTRUCT ps) {
        WinDef.HBRUSH hbrBackground = ExtendedGDI32.INSTANCE.CreateSolidBrush(0);
        ExtendedUser32.INSTANCE.FillRect(hdc, ps.rcPaint, hbrBackground);
        GDI32.INSTANCE.DeleteObject(hbrBackground);
    }

    private static void paintGrid(WinDef.HDC hdc, ExtendedUser32.PAINTSTRUCT ps) {
        WinDef.RECT rect = ps.rcPaint;
        int gridRowCount = currentAttach.gridRowCount();
        int gridColumnCount = currentAttach.gridColumnCount();
        int cellWidth = (rect.right - rect.left) / gridRowCount;
        int cellHeight = (rect.bottom - rect.top) / gridColumnCount;
        int[] polyCounts = new int[gridRowCount + 1 + gridColumnCount + 1];
        WinDef.POINT[] points =
                (WinDef.POINT[]) new WinDef.POINT().toArray(polyCounts.length * 2);
        int lineThickness = currentAttach.gridLineThickness();
        // Vertical lines
        for (int lineIndex = 0; lineIndex <= gridColumnCount; lineIndex++) {
            int x = rect.left + lineIndex * cellWidth;
            if (lineIndex == 0)
                x += lineThickness / 2;
            else if (lineIndex == gridColumnCount)
                x -= lineThickness / 2 + lineThickness % 2;
            points[2 * lineIndex].x = x;
            points[2 * lineIndex].y = rect.top;
            points[2 * lineIndex + 1].x = x;
            points[2 * lineIndex + 1].y = rect.bottom;
            polyCounts[lineIndex] = 2;
        }
        // Horizontal lines
        int polyCountsOffset = gridColumnCount + 1;
        int pointsOffset = 2 * polyCountsOffset;
        for (int lineIndex = 0; lineIndex <= gridRowCount; lineIndex++) {
            points[pointsOffset + 2 * lineIndex].x = rect.left;
            int y = rect.top + lineIndex * cellHeight;
            if (lineIndex == 0)
                y += lineThickness / 2;
            else if (lineIndex == gridRowCount)
                y -= lineThickness / 2 + lineThickness % 2;
            points[pointsOffset + 2 * lineIndex].y = y;
            points[pointsOffset + 2 * lineIndex + 1].x = rect.right;
            points[pointsOffset + 2 * lineIndex + 1].y = y;
            polyCounts[polyCountsOffset + lineIndex] = 2;
        }
        String lineColor = currentAttach.gridLineHexColor();
        WinUser.HPEN gridPen =
                ExtendedGDI32.INSTANCE.CreatePen(ExtendedGDI32.PS_SOLID, lineThickness,
                        hexColorStringToInt(lineColor));
        if (gridPen == null)
            throw new IllegalStateException("Unable to create attach grid pen");
        WinNT.HANDLE oldPen = GDI32.INSTANCE.SelectObject(hdc, gridPen);
        boolean polyPolylineResult = ExtendedGDI32.INSTANCE.PolyPolyline(hdc, points, polyCounts,
                polyCounts.length);
        if (!polyPolylineResult) {
            int lastError = Native.getLastError();
            throw new IllegalStateException(
                    "PolyPolyline failed with error code " + lastError);
        }
        GDI32.INSTANCE.SelectObject(hdc, oldPen);
        GDI32.INSTANCE.DeleteObject(gridPen);
    }

    private static int hexColorStringToInt(String hexColor) {
        if (hexColor.startsWith("#"))
            hexColor = hexColor.substring(1);
        int colorInt = Integer.parseUnsignedInt(hexColor, 16);
        // In COLORREF, the order is 0x00BBGGRR, so we need to reorder the components.
        int red = (colorInt >> 16) & 0xFF;
        int green = (colorInt >> 8) & 0xFF;
        int blue = colorInt & 0xFF;
        return (blue << 16) | (green << 8) | red;
    }

    public static void setIndicatorColor(String hexColor) {
        if (showingIndicator && currentIndicatorHexColor != null &&
            currentIndicatorHexColor.equals(hexColor))
            return;
        currentIndicatorHexColor = hexColor;
        if (hexColor == null) {
            hideIndicator();
            return;
        }
        if (indicatorWindowHwnd == null)
            createIndicatorWindow();
        showingIndicator = true;
        // Force window to repaint to reflect new color
        User32.INSTANCE.InvalidateRect(indicatorWindowHwnd, null, true);
        User32.INSTANCE.UpdateWindow(indicatorWindowHwnd);
        boolean showWindowResult =
                User32.INSTANCE.ShowWindow(indicatorWindowHwnd, WinUser.SW_SHOWNORMAL);
    }

    public static void hideIndicator() {
        if (!showingIndicator)
            return;
        showingIndicator = false;
        User32.INSTANCE.ShowWindow(indicatorWindowHwnd, WinUser.SW_HIDE);
    }

    public static void setAttach(Attach attach) {
        if (showingAttachGrid && currentAttach != null &&
            currentAttach.equals(attach))
            return;
        currentAttach = attach;
        if (attach == null) {
            hideAttachGrid();
            return;
        }
        if (attachGridWindows != null) {
            Set<Monitor> attachGridMonitors = attachGridWindows.stream()
                                                               .map(AttachGridWindow::monitor)
                                                               .collect(
                                                                       Collectors.toSet());
            if (!attachGridMonitors.equals(findMonitors())) {
                // Recreate windows if the monitors have changed.
                for (AttachGridWindow attachGridWindow : attachGridWindows)
                    removeWindow(attachGridWindow.hwnd);
                attachGridWindows = null;
            }
        }
        if (attachGridWindows == null)
            createAttachGridWindows();
        showingAttachGrid = true;
        for (AttachGridWindow attachGridWindow : attachGridWindows) {
            User32.INSTANCE.InvalidateRect(attachGridWindow.hwnd, null, true);
            User32.INSTANCE.UpdateWindow(attachGridWindow.hwnd);
            boolean attachGridWindowShowWindowResult = User32.INSTANCE.ShowWindow(attachGridWindow.hwnd,
                    WinUser.SW_SHOWNORMAL);
        }
    }

    public static void hideAttachGrid() {
        if (!showingAttachGrid)
            return;
        showingAttachGrid = false;
        for (AttachGridWindow attachGridWindow : attachGridWindows)
            User32.INSTANCE.ShowWindow(attachGridWindow.hwnd, WinUser.SW_HIDE);
    }

    public static WinUser.MONITORINFO findCurrentMonitorPosition(
            WinDef.POINT mousePosition) {
        WinUser.HMONITOR hMonitor = User32.INSTANCE.MonitorFromPoint(
                new WinDef.POINT.ByValue(mousePosition.getPointer()),
                WinUser.MONITOR_DEFAULTTONEAREST);
        WinUser.MONITORINFO monitorInfo = new WinUser.MONITORINFO();
        User32.INSTANCE.GetMonitorInfo(hMonitor, monitorInfo);
        return monitorInfo;
    }

    /**
     * Caches the mouse position as an optimization to not have to recreate the POINT object.
     */
    static void mouseMoved(WinDef.POINT mousePosition) {
        WindowsOverlay.mousePosition = mousePosition;
    }

    public static void setMousePosition(double x, double y) {
        if (mousePosition == null)
            return;
        WinUser.MONITORINFO monitorInfo = findCurrentMonitorPosition(mousePosition);
        CursorPositionAndSize cursorPositionAndSize =
                WindowsMouse.cursorPositionAndSize();
        User32.INSTANCE.MoveWindow(indicatorWindowHwnd,
                bestIndicatorX(mousePosition.x, cursorPositionAndSize.width(),
                        monitorInfo.rcMonitor.left, monitorInfo.rcMonitor.right),
                bestIndicatorY(mousePosition.y, cursorPositionAndSize.height(),
                        monitorInfo.rcMonitor.top, monitorInfo.rcMonitor.bottom),
                indicatorSize, indicatorSize, false);
        mousePosition = null;
    }
}
