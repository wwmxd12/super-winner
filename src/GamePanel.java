import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.util.Random;

/**
 * 游戏的真正心脏。它做三件事：
 *   每隔一段时间"走一步"（update）→ 判断吃没吃到、死没死 → 把整个棋盘重新画一遍（draw）
 * 这个"走-画-走-画"的圈，就是所谓的"游戏循环"。
 */
public class GamePanel extends JPanel implements ActionListener {

    // ============ 一、棋盘的物理设定（先把"格子纸"量好尺寸）============
    static final int UNIT_SIZE = 20;                       // 一个格子边长 20 像素
    static final int GAME_UNITS = 25;                      // 横竖各 25 个格子
    static final int PANEL_SIZE = UNIT_SIZE * GAME_UNITS;  // 所以面板是 500 x 500 像素
    static final int DELAY = 130;                          // 每隔 130 毫秒走一步（数字越小蛇越快）

    // ============ 二、蛇和食物的数据 ============
    // 蛇的身体 = 一串坐标。数组下标 0 是蛇头，最后一个是有身长的最后一节。
    // 数组长度开到"格子总数"，因为蛇理论上最长就是把棋盘占满，够用了。
    final int[] snakeX = new int[GAME_UNITS * GAME_UNITS];
    final int[] snakeY = new int[GAME_UNITS * GAME_UNITS];
    int bodyParts;          // 蛇现在有几节
    int applesEaten;        // 吃了几颗苹果，也就是得分
    int appleX;             // 苹果所在格子的左上角像素 x
    int appleY;             // 苹果所在格子的左上角像素 y
    char direction;         // 蛇头朝向：'U'上 'D'下 'L'左 'R'右
    boolean running;        // true = 游戏进行中，false = 已经死了/暂停
    int highScore;          // 本局之前的最高分（重启后保留）

    final Timer timer;                  // 一个"闹钟"，每 DELAY 毫秒 ringing 一次
    final Random random = new Random(); // 用来随机扔苹果

    // ============ 配色 & 字体：想让蛇换个造型，只改这一块就够了 ============
    static final Color BG_DARK    = new Color(14, 16, 14);   // 棋盘格深的那一格
    static final Color BG_LIGHT   = new Color(24, 28, 24);   // 棋盘格浅的那一格
    static final Color SNAKE_HEAD = new Color(126, 235, 96); // 蛇头：嫩绿
    static final Color SNAKE_MID  = new Color(92, 210, 70);  // 身体浅绿条纹
    static final Color SNAKE_DARK = new Color(46, 170, 32);  // 身体深绿条纹
    static final Color APPLE_BODY = new Color(255, 88, 96);  // 苹果本体
    static final Color APPLE_LEAF = new Color(126, 214, 96); // 苹果叶子
    static final Color TONGUE     = new Color(255, 120, 150);// 小舌头

    // 画面里的中文统一用微软雅黑：Consolas 这类纯英文字体不含汉字，会画成方框
    static final Font FONT_SCORE = new Font("Microsoft YaHei", Font.BOLD, 16);
    static final Font FONT_TITLE = new Font("Microsoft YaHei", Font.BOLD, 32);
    static final Font FONT_TIP   = new Font("Microsoft YaHei", Font.PLAIN, 18);

    GamePanel() {
        // 告诉窗口："我需要 500x500 的地方" （SnakeGame 里的 pack() 就是来读这个值）
        setPreferredSize(new Dimension(PANEL_SIZE, PANEL_SIZE));
        setBackground(Color.BLACK);   // 画布底色：黑色
        setFocusable(true);           // 允许这个面板"拿到键盘焦点"，否则收不到按键
        addKeyListener(new TAdapter());// 装上键盘监听器（下面最内层那个类）

        timer = new Timer(DELAY, this); // this 表示"闹钟响了就回调我的 actionPerformed 方法"
        newGame();
        timer.start();                  // 拧开闹钟，游戏循环开始转
    }

    /** 把一局的所有初始状态摆好 */
    private void newGame() {
        direction = 'R';
        bodyParts = 6;
        applesEaten = 0;
        running = true;

        // 初始的蛇：横躺在左上角，占据 (5,5)、(4,5)、(3,5)... 这些格子，头在最右边
        // 注意 snakeX[0] 是蛇头，越往后越靠近蛇尾
        for (int i = 0; i < bodyParts; i++) {
            snakeX[i] = (5 - i) * UNIT_SIZE;
            snakeY[i] = 5 * UNIT_SIZE;
        }
        newApple();
    }

    /** 在棋盘上随机扔一颗苹果，且不能扔到蛇身上 */
    private void newApple() {
        while (true) {
            appleX = random.nextInt(GAME_UNITS) * UNIT_SIZE; // 先选第几列，再换算成像素
            appleY = random.nextInt(GAME_UNITS) * UNIT_SIZE; // 先选第几行，再换算成像素

            // 检查这个格子有没有被蛇身占用，没有才作数
            boolean onSnake = false;
            for (int i = 0; i < bodyParts; i++) {
                if (snakeX[i] == appleX && snakeY[i] == appleY) {
                    onSnake = true;
                    break;
                }
            }
            if (!onSnake) {
                return; // 位置干净，苹果放下，方法结束
            }
        }
    }

    // ============ 三、闹钟响了就走这一步 ============

    /**
     * Timer 每 130 毫秒调用它一次。这就是"游戏的一帧"。
     * 注意：这个方法是 Swing 帮你调用的，你自己永远不会手写 timer 去叫它。
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (running) {
            move();       // 1. 更新状态：蛇挪一格、判断吃到/撞死
        }
        repaint();        // 2. 请求重画画面（它会自动回调下面的 paintComponent）
    }

    /** 核心：让蛇往前挪一格 */
    private void move() {
        // 【第 1 步】身体依次前移：第 i 节跑到第 i-1 节的位置，整条蛇像蠕动一样往前跟
        for (int i = bodyParts; i > 0; i--) {
            snakeX[i] = snakeX[i - 1];
            snakeY[i] = snakeY[i - 1];
        }

        // 【第 2 步】单独算蛇头的新位置（头是最先动的那个，其它节只是"跟上"）
        switch (direction) {
            case 'U' -> snakeY[0] = snakeY[0] - UNIT_SIZE;
            case 'D' -> snakeY[0] = snakeY[0] + UNIT_SIZE;
            case 'L' -> snakeX[0] = snakeX[0] - UNIT_SIZE;
            case 'R' -> snakeX[0] = snakeX[0] + UNIT_SIZE;
        }

        // 【第 3 步】吃到苹果了吗？吃到了就把尾巴"还回来"，于是蛇变长一节
        // 为什么是判断第 0 节？因为经过第 1 步的蠕动，原来的第 1 节已经顶到蛇头的位置上了。
        if (snakeX[0] == appleX && snakeY[0] == appleY) {
            bodyParts++;
            applesEaten++;
            newApple();
        }

        // 【第 4 步】撞墙或撞自己？
        checkCollisions();
    }

    private void checkCollisions() {
        // 情况 A：蛇头撞到四条边框（像素坐标跑出 0 ~ 500 之外）
        if (snakeX[0] < 0 || snakeX[0] >= PANEL_SIZE
                || snakeY[0] < 0 || snakeY[0] >= PANEL_SIZE) {
            running = false;
        }

        // 情况 B：蛇头撞到自己身上。
        // 从 i = 1 开始比，因为第 0 节就是蛇头自己，不能跟自己撞。
        for (int i = 1; i < bodyParts; i++) {
            if (snakeX[i] == snakeX[0] && snakeY[i] == snakeY[0]) {
                running = false;
            }
        }

        // 死了就关掉闹钟，游戏循环停止转动
        if (!running) {
            timer.stop();
        }
    }

    // ============ 四、把当前状态画到屏幕上 ============

    /**
     * Swing 负责调用它来"画一版画面"。我们只管画，不用管什么时候画。
     * g 可以想象成"一支笔"，所有图形都靠它落色。
     */
    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g); // 先按规矩把上一帧擦干净（不清的话蛇会有残影）

        // 把笔升级成"马克笔" Graphics2D，并打开抗锯齿开关——
        // 不开的话，画出来的圆和圆角全是马赛克阶梯；开了才滑，这是"变可爱"的第一步。
        Graphics2D g2 = (Graphics2D) g;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        draw(g2);
    }

    /** 一帧画面的总调度：从最底层往上涂，后面的会盖住前面的 */
    private void draw(Graphics2D g2) {
        drawBoard(g2); // 1. 最底层：棋盘格

        if (!running) {
            // 死了：先蒙一层半透明的黑纱，蛇和字再画在纱上面，就不会变暗
            g2.setColor(new Color(0, 0, 0, 150));
            g2.fillRect(0, 0, PANEL_SIZE, PANEL_SIZE);
        }

        drawApple(g2);           // 2. 苹果
        drawSnake(g2, running);  // 3. 蛇（死了会换成 X X 晕圈圈眼）

        if (running) {
            drawScore(g2);
        } else {
            gameOver(g2);
        }
    }

    /** 背景：一层若隐若现的棋盘格，像小朋友的爬行垫 */
    private void drawBoard(Graphics2D g2) {
        for (int row = 0; row < GAME_UNITS; row++) {
            for (int col = 0; col < GAME_UNITS; col++) {
                // 行号 + 列号为偶数就涂深的那格，奇数涂浅的那格，交错出来就是棋盘格
                g2.setColor(((row + col) % 2 == 0) ? BG_DARK : BG_LIGHT);
                g2.fillRect(col * UNIT_SIZE, row * UNIT_SIZE, UNIT_SIZE, UNIT_SIZE);
            }
        }
    }

    /** 苹果：圆果子 + 亮晶晶的高光 + 头顶一片小叶子 */
    private void drawApple(Graphics2D g2) {
        int x = appleX;
        int y = appleY;

        // 果子本体（往下缩 3 像素，给头顶的叶子留出位置）
        g2.setColor(APPLE_BODY);
        g2.fillOval(x + 1, y + 3, UNIT_SIZE - 2, UNIT_SIZE - 4);

        // 高光：左上角一个淡色小圆点，立刻有"刚洗过、亮晶晶"的感觉
        g2.setColor(new Color(255, 190, 195));
        g2.fillOval(x + 4, y + 6, 4, 3);

        // 小叶子：斜贴在头顶
        g2.setColor(APPLE_LEAF);
        g2.fillOval(x + UNIT_SIZE / 2 - 1, y, 7, 4);
    }

    /** 蛇：一节一节圆滚滚的"扭扭糖"，脑袋上还有会看方向的眼睛 */
    private void drawSnake(Graphics2D g2, boolean alive) {
        // ---- 身体：从尾巴往蛇头画，深绿浅绿隔节相间 ----
        for (int i = bodyParts - 1; i >= 1; i--) {
            g2.setColor((i % 2 == 0) ? SNAKE_MID : SNAKE_DARK);
            // 每一节都向内缩 1 像素，节与节之间留出一道深色的缝，一节一节才看得出来；
            // 最后一节（尾巴）多缩几像素，收出一个小尖，就不像被刀切平的了。
            int pad = (i == bodyParts - 1) ? 5 : 1;
            g2.fillRoundRect(snakeX[i] + pad, snakeY[i] + pad,
                    UNIT_SIZE - pad * 2, UNIT_SIZE - pad * 2, 12, 12);
        }

        // ---- 蛇头：比身体大一圈，脑袋显得圆圆的 ----
        int hx = snakeX[0];
        int hy = snakeY[0];
        g2.setColor(SNAKE_HEAD);
        g2.fillRoundRect(hx - 1, hy - 1, UNIT_SIZE + 2, UNIT_SIZE + 2, 14, 14);

        drawFace(g2, hx, hy, alive);
    }

    /**
     * 蛇头的表情。alive = true 是圆眼睛 + 吐小舌头；false 是两个 X 当眼睛（晕过去了）。
     *
     * 麻烦之处在于：蛇朝哪个方向走，眼睛和舌头就得挪到哪一边。
     * 如果为四个方向各写一遍坐标，代码会又长又容易错。这里用两个"方向向量"解决：
     *   forward(fx, fy) = 脸正前方的单位向量，比如朝右就是 (1, 0)
     *   side   (sx, sy) = 左右分开两只眼的单位向量，永远和 forward 垂直
     * 这样任何方向都只算一次坐标，换方向只是把这两个向量里的 1 换成 -1。
     */
    private void drawFace(Graphics2D g2, int hx, int hy, boolean alive) {
        int fx = 0, fy = 0;
        int sx = 0, sy = 0;
        switch (direction) {
            case 'U' -> { fy = -1; sx = 1; } // 朝上：脸朝上，两只眼左右排
            case 'D' -> { fy = 1; sx = 1; }
            case 'L' -> { fx = -1; sy = 1; } // 朝左：脸朝左，两只眼上下排
            case 'R' -> { fx = 1; sy = 1; }
        }

        int cx = hx + UNIT_SIZE / 2; // 蛇头的中心点，下面所有五官都拿它当基准
        int cy = hy + UNIT_SIZE / 2;

        // 左右两只眼：side 取 -1 和 +1，就是在中心的基础上往两边各挪 4 像素
        for (int side = -1; side <= 1; side += 2) {
            int ex = cx + fx * 3 + sx * 4 * side;
            int ey = cy + fy * 3 + sy * 4 * side;

            if (alive) {
                g2.setColor(Color.WHITE);             // 眼白
                g2.fillOval(ex - 3, ey - 3, 6, 6);
                g2.setColor(new Color(40, 40, 40));   // 瞳孔往前挪 1 像素 = 眼神盯着要去的方向
                g2.fillOval(ex - 2 + fx, ey - 2 + fy, 4, 4);
            } else {
                g2.setColor(Color.WHITE);
                g2.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
                g2.drawLine(ex - 3, ey - 3, ex + 3, ey + 3);
                g2.drawLine(ex + 3, ey - 3, ex - 3, ey + 3);
            }
        }

        if (!alive) {
            return; // 晕过去的时候舌头就收起来，不吐了
        }

        // 小舌头：从嘴边伸出去，末端分个叉，就是"嘶嘶"那个感觉
        g2.setColor(TONGUE);
        g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        int mx = cx + fx * 10; // 舌头根，贴在嘴边
        int my = cy + fy * 10;
        int tx = cx + fx * 16; // 舌头尖
        int ty = cy + fy * 16;
        g2.drawLine(mx, my, tx, ty);
        // 分叉的两个小尖：在"往前"的基础上，再各自往左右偏 2 像素
        g2.drawLine(tx, ty, tx + fx * 3 + sx * 2, ty + fy * 3 + sy * 2);
        g2.drawLine(tx, ty, tx + fx * 3 - sx * 2, ty + fy * 3 - sy * 2);
    }

    /** 左下角得分、右下角最高分 */
    private void drawScore(Graphics2D g2) {
        g2.setColor(Color.WHITE);
        g2.setFont(FONT_SCORE);
        g2.drawString("得分 " + applesEaten, UNIT_SIZE, PANEL_SIZE - UNIT_SIZE / 2);

        // 最高分靠右对齐：先量出这行字有多宽，再从右边减掉它，数字变长也不会撑破屏幕
        String text = "最高分 " + highScore;
        g2.drawString(text, PANEL_SIZE - UNIT_SIZE - g2.getFontMetrics().stringWidth(text),
                PANEL_SIZE - UNIT_SIZE / 2);
    }

    private void gameOver(Graphics2D g2) {
        // 顺手把最高分记下来，供下一局比较
        if (applesEaten > highScore) {
            highScore = applesEaten;
        }

        drawCentered(g2, "蛇蛇晕过去了", FONT_TITLE, new Color(255, 180, 110), PANEL_SIZE / 2 - 40);
        drawCentered(g2, "本局得分 " + applesEaten, FONT_TIP, Color.WHITE, PANEL_SIZE / 2 + 5);
        drawCentered(g2, "按空格键再来一次", FONT_TIP, new Color(180, 255, 170), PANEL_SIZE / 2 + 35);
    }

    /** 小工具：把一行字在 500 像素宽的面板上水平居中 */
    private void drawCentered(Graphics2D g2, String text, Font font, Color color, int baselineY) {
        g2.setFont(font);
        g2.setColor(color);
        g2.drawString(text, (PANEL_SIZE - g2.getFontMetrics().stringWidth(text)) / 2, baselineY);
    }

    // ============ 五、键盘：把"按了哪个键"翻译成"蛇该往哪走" ============

    /**
     * 内部类：一个专职听键盘的小工。TAdapter 继承自 KeyAdapter，
     * KeyAdapter 已经把另外两个没用的方法（按下/释放）空实现了，我们只重写"按下"。
     */
    class TAdapter extends KeyAdapter {
        @Override
        public void keyPressed(KeyEvent e) {
            int code = e.getKeyCode(); // 拿到按键的编号，比如 VK_LEFT 是个整数常量

            // 死了的时候，只认空格 = 重开一局
            if (!running) {
                if (code == KeyEvent.VK_SPACE) {
                    newGame();
                    timer.start(); // 重新拧开闹钟
                }
                return;
            }

            // 关键设计：只允许"垂直方向"的按键立刻生效。
            // 因为蛇每 130 毫秒才走一步，如果允许直接改成反方向，
            // 就会出现"蛇头撞进自己脖子"的 bug，所以这里禁止 180 度掉头。
            switch (code) {
                case KeyEvent.VK_LEFT, KeyEvent.VK_A -> {
                    if (direction != 'L' && direction != 'R') {
                        direction = 'L';
                    }
                }
                case KeyEvent.VK_RIGHT, KeyEvent.VK_D -> {
                    if (direction != 'R' && direction != 'L') {
                        direction = 'R';
                    }
                }
                case KeyEvent.VK_UP, KeyEvent.VK_W -> {
                    if (direction != 'U' && direction != 'D') {
                        direction = 'U';
                    }
                }
                case KeyEvent.VK_DOWN, KeyEvent.VK_S -> {
                    if (direction != 'D' && direction != 'U') {
                        direction = 'D';
                    }
                }
            }
        }
    }
}
