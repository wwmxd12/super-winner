import javax.swing.JFrame;

/**
 * 这个类只干一件事：造一个"窗口壳子"，把游戏面板塞进去，然后显示出来。
 * 真正的游戏逻辑在 GamePanel 里，这里不掺和。
 */
public class SnakeGame {

    public static void main(String[] args) {
        // 1. new 一个窗口。JFrame 你可以理解成"一个空的相框"
        JFrame frame = new JFrame("贪吃蛇 Snake");

        // 2. 往相框里放游戏面板（游戏画面就长在这个面板上）
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE); // 点右上角关闭按钮就退出程序
        frame.setResizable(false);                           // 不让用户拖拽改变窗口大小，省得画面错位
        frame.add(new GamePanel());                          // 关键：把面板放进窗口

        // 3. "要多大就多大"——按面板自己报的尺寸来定窗口大小
        frame.pack();
        frame.setLocationRelativeTo(null); // null 表示居中显示
        frame.setVisible(true);            // 上面都是组装，这一行才是"掀开布帘子"让窗口露面
    }
}
