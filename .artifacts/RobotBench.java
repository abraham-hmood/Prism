import java.awt.*;
public class RobotBench {
  public static void main(String[] a) throws Exception {
    Robot r = new Robot();
    Rectangle b = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
    int x = b.x + b.width/2, y = b.y + b.height/2;
    r.mouseMove(x, y);
    long t0 = System.nanoTime();
    int n = 200;
    for (int i = 0; i < n; i++) r.mouseMove(x + (i % 60) * 3, y + (i % 40) * 2);
    long t1 = System.nanoTime();
    System.out.println("mouseMove avg ms = " + ((t1-t0)/1e6/n));
    long g0 = System.nanoTime();
    for (int i = 0; i < 50; i++) GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()[0].getDefaultConfiguration().getBounds();
    long g1 = System.nanoTime();
    System.out.println("screen bounds avg ms = " + ((g1-g0)/1e6/50));
  }
}
