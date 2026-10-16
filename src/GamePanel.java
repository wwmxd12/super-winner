import javax.swing.JPanel;
import javax.swing.Timer;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.Random;

/**
 * 游戏的真正心脏：《向僵尸开炮》。
 * 底部一台炮台自动索敌开火，僵尸从上方一波波涌下来啃城墙。
 * 每 16 毫秒"走一步"(update) → 移动炮台/子弹/僵尸、判命中、算升级 → 重画整屏(draw)。
 * 这份是 docs/index.html 网页版的"Java 孪生版"，方法名和执行顺序刻意两边一一对应。
 */
public class GamePanel extends JPanel implements ActionListener {

    // ============ 一、战场物理设定 ============
    static final int W = 480;             // 逻辑宽
    static final int H = 640;             // 逻辑高
    static final int WALL_Y = H - 56;     // 城墙顶沿线：僵尸越过这条线就啃墙
    static final int CANNON_Y = WALL_Y + 6;
    static final int DELAY = 16;          // 每 16 毫秒一帧 ≈ 60fps，图个丝滑
    static final double DT = DELAY;       // 逻辑里统一用这个固定步长（毫秒）
    static final double MOVE_SPEED = 380; // 键盘平移速度 px/s

    // ============ 二、配色表：想换造型只改这里 ============
    static final Color SKY_TOP   = new Color(16, 20, 28);
    static final Color SKY_BOT   = new Color(26, 32, 44);
    static final Color GRID      = new Color(40, 50, 66);
    static final Color WALL_TOP  = new Color(88, 98, 116);
    static final Color WALL_BODY = new Color(54, 62, 78);
    static final Color CANNON    = new Color(120, 170, 232);
    static final Color CANNON_DK = new Color(54, 96, 158);
    static final Color BARREL    = new Color(206, 224, 248);
    static final Color MUZZLE    = new Color(255, 226, 128);
    static final Color BULLET    = new Color(255, 214, 92);
    static final Color HP        = new Color(92, 220, 130);
    static final Color HP_LOW    = new Color(240, 96, 96);
    static final Color XP        = new Color(122, 196, 255);
    static final Color TEXT_DIM  = new Color(150, 166, 184);
    static final Color CARD_BG   = new Color(30, 38, 52);
    static final Color CARD_EDGE = new Color(120, 170, 232);

    static final Color Z_WALKER = new Color(96, 176, 92);
    static final Color Z_RUNNER = new Color(208, 150, 70);
    static final Color Z_TANK   = new Color(150, 84, 170);
    static final Color Z_BOSS   = new Color(220, 72, 84);

    // 画面里的中文统一用微软雅黑：纯英文字体不含汉字会画成方框
    static final Font FONT_HUD   = new Font("Microsoft YaHei", Font.BOLD, 18);
    static final Font FONT_SMALL = new Font("Microsoft YaHei", Font.PLAIN, 13);
    static final Font FONT_TINY  = new Font("Microsoft YaHei", Font.PLAIN, 11);
    static final Font FONT_TITLE = new Font("Microsoft YaHei", Font.BOLD, 40);
    static final Font FONT_SUB   = new Font("Microsoft YaHei", Font.BOLD, 26);
    static final Font FONT_BADGE = new Font("Microsoft YaHei", Font.BOLD, 14);

    // ============ 三、玩家（炮台 + 成长属性）============
    double px, wantX, aim;   // 炮台中心 x、想去的 x、炮管朝向(弧度)
    final int PR = 20;       // 炮台碰撞半径
    int hp, maxHp;
    double dmg, fireInterval, bulletSpeed;
    int shots, pierce;
    double slowPct;
    int xp, level, xpNext;
    double fireCd, flash, shake;

    // ============ 四、战局数据 ============
    int wave;
    double waveTimer, spawnCd;
    int kills, score;
    int highScore = 0;

    // 状态：0=待开始 1=进行中 2=升级选卡 3=结束
    static final int READY = 0, PLAYING = 1, LEVELUP = 2, OVER = 3;
    int state = READY;

    final ArrayList<Zombie> zombies = new ArrayList<>();
    final ArrayList<Bullet> bullets = new ArrayList<>();
    final ArrayList<Part> parts = new ArrayList<>();
    final ArrayList<Floater> floaters = new ArrayList<>();
    final ArrayList<Integer> cardIdx = new ArrayList<>();   // 当前三张卡对应的升级编号
    final int[] cardX = new int[3], cardY = new int[3], cardW = new int[3], cardH = new int[3];
    String overMsg = "";

    final Timer timer;
    final Random random = new Random();

    // ============ 五、稀有度与强化卡池 ============
    // 稀有度：N 常见 → R → SR → SSR → UR → EX 神话。RAR_W 是抽卡权重（越大越常见）
    static final String[] RAR_NAME = {"N", "R", "SR", "SSR", "UR", "EX"};
    static final int[] RAR_W = {100, 55, 26, 11, 4, 2};
    static final Color[] RAR_COL = {
        new Color(150, 166, 184), new Color(86, 156, 255), new Color(178, 110, 240),
        new Color(255, 176, 64),  new Color(255, 86, 120), new Color(64, 224, 208),
    };
    // 强化卡池：同一效果按稀有度分几档，U_RAR[i] 指向稀有度下标；applyUpgrade 按 i 分支
    static final String[] U_TITLE = {
        "火力强化", "高爆弹头", "湮灭弹头",
        "高射炮", "加特林", "死亡风暴",
        "提速弹匣", "电磁轨道",
        "分裂炮管", "多重炮管", "弹幕领域",
        "穿甲弹", "磁轨贯穿",
        "冰缓立场", "时间凝滞",
        "修补城墙", "加固城墙", "合金长城",
        "双管齐下", "天基裁决",
    };
    static final String[] U_DESC = {
        "单发伤害 +25%", "单发伤害 +45%", "单发伤害 +75%",
        "攻速 +12%", "攻速 +22%", "攻速 +35%",
        "弹速 +18%", "弹速 +40%",
        "弹幕 +1 发", "弹幕 +2 发", "弹幕 +3 发",
        "穿透 +1", "穿透 +2",
        "僵尸减速 +10%", "僵尸减速 +22%",
        "立即回复 35 血", "上限 +30 并回 30", "上限 +70 并回满",
        "弹幕 +2 且攻速 +15%", "伤害 +120% 且穿透 +1",
    };
    static final int[] U_RAR = {
        0, 1, 2,
        0, 1, 2,
        0, 2,
        1, 2, 3,
        1, 3,
        1, 3,
        0, 1, 2,
        4, 5,
    };

    // 三种小实体，各用一个静态内部类装数据（相当于网页版里的对象字面量）
    static class Zombie {
        double x, y, r, hp, maxHp, spd, wob, flash;
        String type;
        Color col;
        int pts;
    }
    static class Bullet {
        double x, y, vx, vy, dmg;
        int pierce, hit;
        double r = 4;
    }
    static class Part {
        double x, y, vx, vy, life, max, sz;
        Color col;
    }
    static class Floater {
        double x, y, vy, life, max, size;
        String text;
        Color col;
    }

    GamePanel() {
        setPreferredSize(new Dimension(W, H));
        setBackground(Color.BLACK);
        setFocusable(true);
        addKeyListener(new TAdapter());
        addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e) { handlePress(e.getX(), e.getY()); }
        });
        addMouseMotionListener(new MouseMotionAdapter() {
            @Override public void mouseMoved(MouseEvent e) { handleMove(e.getX()); }
            @Override public void mouseDragged(MouseEvent e) { handleMove(e.getX()); }
        });

        timer = new Timer(DELAY, this);
        startGame();     // 摆好初始状态
        state = READY;   // 开局先进"待开始"画面，等空格/点击
        timer.start();
    }

    /** 把一局的所有初始状态摆好 */
    private void startGame() {
        px = wantX = W / 2.0;
        aim = -Math.PI / 2;
        hp = maxHp = 100;
        dmg = 6; fireInterval = 320; bulletSpeed = 460;
        shots = 1; pierce = 0; slowPct = 0;
        xp = 0; level = 1; xpNext = 12;
        fireCd = 0; flash = 0; shake = 0;
        wave = 1; waveTimer = 0; spawnCd = 300; kills = 0; score = 0;
        zombies.clear(); bullets.clear(); parts.clear(); floaters.clear(); cardIdx.clear();
        state = PLAYING;
    }

    private void gameOver() {
        state = OVER;
        overMsg = "撑到第 " + wave + " 波 · 击杀 " + kills;
        if (score > highScore) highScore = score;
    }

    // ============ 六、每帧回调 ============
    @Override
    public void actionPerformed(ActionEvent e) {
        if (state == PLAYING) {
            update(DT);
        } else {
            tickEffects(DT);   // 暂停/选卡/结束时也让粒子和飘字收个尾
        }
        repaint();
    }

    /** 一帧的全部逻辑：跟网页版 update(dt) 一步步对应 */
    private void update(double dt) {
        // 键盘左右键持续推 wantX
        if (keyLeft) wantX -= MOVE_SPEED * dt / 1000;
        if (keyRight) wantX += MOVE_SPEED * dt / 1000;
        clampCannon();
        // 炮台实际位置朝 wantX 平滑靠拢
        px += (wantX - px) * Math.min(1, dt / 90);

        tickWave(dt);
        tickSpawn(dt);
        tickCannon(dt);
        tickBullets(dt);
        tickZombies(dt);
        tickEffects(dt);
    }

    private void clampCannon() {
        wantX = clamp(wantX, PR + 4, W - PR - 4);
    }

    // ---- 波次与出场 ----
    private double waveHp() { return 1 + (wave - 1) * 0.32; }
    private double spawnGap() { return Math.max(260, 1050 - wave * 68); }

    private void tickWave(double dt) {
        waveTimer += dt;
        if (waveTimer >= 16000) {
            waveTimer = 0;
            wave++;
            if (wave % 5 == 0) spawnZombie("boss");
            floatUp(W / 2, 96, "第 " + wave + " 波", MUZZLE, 22);
        }
    }

    private void tickSpawn(double dt) {
        spawnCd -= dt;
        if (spawnCd <= 0) {
            spawnCd = spawnGap();
            double roll = random.nextDouble();
            String type;
            if (wave >= 3 && roll < 0.16) type = "runner";
            else if (wave >= 4 && roll < 0.28) type = "tank";
            else if (roll < 0.7) type = "walker";
            else type = "runner";
            spawnZombie(type);
        }
    }

    private void spawnZombie(String type) {
        double br, hpMul, spdMul, pts;
        Color col;
        switch (type) {
            case "runner" -> { br = 11; hpMul = 0.6; spdMul = 1.9; pts = 15; col = Z_RUNNER; }
            case "tank"   -> { br = 22; hpMul = 3.4; spdMul = 0.62; pts = 30; col = Z_TANK; }
            case "boss"   -> { br = 38; hpMul = 20;  spdMul = 0.5;  pts = 300; col = Z_BOSS; }
            default       -> { br = 15; hpMul = 1.0; spdMul = 1.0;  pts = 10; col = Z_WALKER; }
        }
        Zombie z = new Zombie();
        z.type = type;
        z.r = br;
        z.x = rand(30, W - 30);
        z.y = -z.r;
        z.hp = z.maxHp = hpMul * waveHp();
        z.spd = rand(38, 52) * spdMul;
        z.wob = rand(0, 6.28);
        z.flash = 0;
        z.col = col;
        z.pts = (int) pts;
        zombies.add(z);
        if (type.equals("boss")) shake = Math.max(shake, 8);
    }

    // ---- 炮台索敌与开火 ----
    private Zombie nearestZombie() {
        Zombie best = null;
        double bd = Double.MAX_VALUE;
        for (Zombie z : zombies) {
            double d = Math.hypot(z.x - px, z.y - CANNON_Y);
            if (d < bd) { bd = d; best = z; }
        }
        return best;
    }

    private void tickCannon(double dt) {
        Zombie tgt = nearestZombie();
        double aimTo = (tgt == null) ? -Math.PI / 2 : Math.atan2(tgt.y - CANNON_Y, tgt.x - px);
        double diff = aimTo - aim;
        while (diff > Math.PI) diff -= Math.PI * 2;
        while (diff < -Math.PI) diff += Math.PI * 2;
        aim += diff * Math.min(1, dt / 60);

        fireCd -= dt;
        if (fireCd <= 0 && tgt != null) {
            fireVolley();
            fireCd = fireInterval;
        }
    }

    private void fireVolley() {
        int n = shots;
        double gap = 11;                                   // 多弹横向间隔：方向完全一致、只是左右并排，飞多远都还是一束，不会散开打空
        double mx = px + Math.cos(aim) * (PR + 8);         // 炮口中心
        double my = CANNON_Y + Math.sin(aim) * (PR + 8);
        double nx = -Math.sin(aim);                        // 垂直于瞄准方向的“横向”单位向量，用来左右排开
        double ny = Math.cos(aim);
        double vx = Math.cos(aim) * bulletSpeed;           // 所有子弹同向平行
        double vy = Math.sin(aim) * bulletSpeed;
        for (int i = 0; i < n; i++) {
            double off = (i - (n - 1) / 2.0) * gap;        // 相对中心左右对称排开
            Bullet b = new Bullet();
            b.x = mx + nx * off; b.y = my + ny * off;
            b.vx = vx; b.vy = vy;
            b.dmg = dmg; b.pierce = pierce; b.hit = 0;
            bullets.add(b);
        }
        flash = 90;
    }

    private void tickBullets(double dt) {
        for (int i = bullets.size() - 1; i >= 0; i--) {
            Bullet b = bullets.get(i);
            b.x += b.vx * dt / 1000;
            b.y += b.vy * dt / 1000;
            if (b.x < -20 || b.x > W + 20 || b.y < -20 || b.y > H + 20) {
                bullets.remove(i); continue;
            }
            for (int j = zombies.size() - 1; j >= 0; j--) {
                Zombie z = zombies.get(j);
                if (Math.hypot(b.x - z.x, b.y - z.y) < z.r + b.r) {
                    hitZombie(z, j, b.dmg);
                    b.hit++;
                    if (b.hit > b.pierce) bullets.remove(i);
                    break;
                }
            }
        }
    }

    private void tickZombies(double dt) {
        double slow = 1 - slowPct / 100;
        for (int i = zombies.size() - 1; i >= 0; i--) {
            Zombie z = zombies.get(i);
            z.y += z.spd * slow * dt / 1000;
            z.wob += dt / 1000 * 6;
            if (z.flash > 0) z.flash -= dt;
            if (z.y + z.r >= WALL_Y) {
                int bite = switch (z.type) {
                    case "boss" -> 26;
                    case "tank" -> 12;
                    default -> 7;
                };
                damageWall(bite);
                burst(z.x, WALL_Y, new Color(190, 60, 70), 10);
                zombies.remove(i);
            }
        }
    }

    private void hitZombie(Zombie z, int index, double d) {
        z.hp -= d;
        z.flash = 90;
        floatUp(z.x, z.y - z.r, "-" + Math.round(d), Color.WHITE, 12);
        if (z.hp <= 0) killZombie(z, index);
    }

    private void killZombie(Zombie z, int index) {
        score += z.pts;
        kills++;
        burst(z.x, z.y, z.col, z.type.equals("boss") ? 26 : 12);
        gainXp(z.type.equals("boss") ? 8 : 1);
        if (z.type.equals("boss")) shake = Math.max(shake, 10);
        zombies.remove(index);
    }

    private void damageWall(int amount) {
        hp -= amount;
        shake = Math.max(shake, 7);
        floatUp(W / 2, WALL_Y - 12, "-" + amount, HP_LOW, 16);
        if (hp <= 0) { hp = 0; gameOver(); }
    }

    // ---- 升级选卡 ----
    private void gainXp(int n) {
        xp += n;
        while (xp >= xpNext) {
            xp -= xpNext;
            level++;
            xpNext = (int) Math.round(xpNext * 1.4) + 4;
            openLevelUp();
        }
    }

    private void openLevelUp() {
        cardIdx.clear();
        ArrayList<Integer> pool = new ArrayList<>();
        for (int i = 0; i < U_TITLE.length; i++) pool.add(i);
        // 按稀有度权重抽 3 张不重复的卡（EX 极稀有）
        for (int k = 0; k < 3 && !pool.isEmpty(); k++) {
            int total = 0;
            for (int idx : pool) total += RAR_W[U_RAR[idx]];
            int roll = random.nextInt(total), pick = 0;
            for (int p = 0; p < pool.size(); p++) {
                roll -= RAR_W[U_RAR[pool.get(p)]];
                if (roll < 0) { pick = p; break; }
            }
            cardIdx.add(pool.remove(pick));
        }
        int cw = 300, ch = 62, gap = 14;
        int totalH = cardIdx.size() * ch + (cardIdx.size() - 1) * gap;
        int y0 = (H - totalH) / 2;
        for (int i = 0; i < cardIdx.size(); i++) {
            cardX[i] = (W - cw) / 2; cardY[i] = y0 + i * (ch + gap); cardW[i] = cw; cardH[i] = ch;
        }
        state = LEVELUP;
    }

    private void chooseCard(int i) {
        if (i < 0 || i >= cardIdx.size()) return;
        applyUpgrade(cardIdx.get(i));
        floatUp(W / 2, H / 2 - 60, U_TITLE[cardIdx.get(i)], XP, 20);
        state = PLAYING;
    }

    private void applyUpgrade(int i) {
        switch (i) {
            case 0 -> dmg *= 1.25;
            case 1 -> dmg *= 1.45;
            case 2 -> dmg *= 1.75;
            case 3 -> fireInterval = Math.max(60, fireInterval * 0.89);
            case 4 -> fireInterval = Math.max(60, fireInterval * 0.80);
            case 5 -> fireInterval = Math.max(60, fireInterval * 0.70);
            case 6 -> bulletSpeed *= 1.18;
            case 7 -> bulletSpeed *= 1.40;
            case 8 -> shots += 1;
            case 9 -> shots += 2;
            case 10 -> shots += 3;
            case 11 -> pierce += 1;
            case 12 -> pierce += 2;
            case 13 -> slowPct = Math.min(75, slowPct + 10);
            case 14 -> slowPct = Math.min(75, slowPct + 22);
            case 15 -> hp = Math.min(maxHp, hp + 35);
            case 16 -> { maxHp += 30; hp = Math.min(maxHp, hp + 30); }
            case 17 -> { maxHp += 70; hp = maxHp; }
            case 18 -> { shots += 2; fireInterval = Math.max(60, fireInterval * 0.87); }
            case 19 -> { dmg *= 2.2; pierce += 1; }
        }
    }

    // ---- 粒子与飘字 ----
    private void burst(double x, double y, Color col, int n) {
        for (int i = 0; i < n; i++) {
            Part p = new Part();
            double a = rand(0, 6.28), s = rand(40, 220);
            p.x = x; p.y = y;
            p.vx = Math.cos(a) * s; p.vy = Math.sin(a) * s;
            p.life = p.max = rand(280, 620);
            p.col = col; p.sz = rand(2, 4);
            parts.add(p);
        }
    }

    private void floatUp(double x, double y, String text, Color col, double size) {
        Floater f = new Floater();
        f.x = x; f.y = y; f.vy = -34; f.life = f.max = 700;
        f.text = text; f.col = col; f.size = size;
        floaters.add(f);
    }

    private void tickEffects(double dt) {
        for (int i = parts.size() - 1; i >= 0; i--) {
            Part p = parts.get(i);
            p.x += p.vx * dt / 1000; p.y += p.vy * dt / 1000;
            p.vy += 520 * dt / 1000;
            p.life -= dt;
            if (p.life <= 0) parts.remove(i);
        }
        for (int i = floaters.size() - 1; i >= 0; i--) {
            Floater f = floaters.get(i);
            f.y += f.vy * dt / 1000;
            f.life -= dt;
            if (f.life <= 0) floaters.remove(i);
        }
        if (flash > 0) flash -= dt;
        if (shake > 0) shake = Math.max(0, shake - dt / 40);
    }

    // ============ 七、把当前状态画到屏幕上 ============
    @Override
    protected void paintComponent(Graphics gg) {
        super.paintComponent(gg);
        Graphics2D g2 = (Graphics2D) gg;
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        draw(g2);
    }

    private void draw(Graphics2D g2) {
        // 屏幕震动：整体平移一点点，制造打击感
        double ox = shake > 0 ? rand(-shake, shake) : 0;
        double oy = shake > 0 ? rand(-shake, shake) : 0;
        g2.translate(ox, oy);

        drawBackground(g2);
        drawWall(g2);
        drawParts(g2);
        for (Zombie z : zombies) drawZombie(g2, z);
        drawBullets(g2);
        drawCannon(g2);
        drawFloaters(g2);
        drawHud(g2);

        g2.translate(-ox, -oy);   // 覆盖层不跟着震

        if (state == LEVELUP) drawLevelUp(g2);
        else if (state == READY || state == OVER) drawOverlay(g2);
    }

    private void drawBackground(Graphics2D g2) {
        GradientPaint grd = new GradientPaint(0, 0, SKY_TOP, 0, H, SKY_BOT);
        g2.setPaint(grd);
        g2.fillRect(0, 0, W, H);

        g2.setColor(withAlpha(GRID, 0.25f));
        for (int x = 0; x <= W; x += 40) g2.drawLine(x, 0, x, WALL_Y);
        for (int y = 0; y <= WALL_Y; y += 40) g2.drawLine(0, y, W, y);
    }

    private void drawWall(Graphics2D g2) {
        g2.setColor(WALL_BODY); g2.fillRect(0, WALL_Y, W, H - WALL_Y);
        g2.setColor(WALL_TOP);  g2.fillRect(0, WALL_Y, W, 8);

        double p = clamp(hp / (double) maxHp, 0, 1);
        g2.setColor(new Color(20, 24, 32)); g2.fillRect(10, WALL_Y + 14, W - 20, 14);
        g2.setColor(p > 0.35 ? HP : HP_LOW); g2.fillRect(10, WALL_Y + 14, (int) ((W - 20) * p), 14);
        g2.setColor(Color.BLACK); g2.setStroke(new BasicStroke(2));
        g2.drawRect(10, WALL_Y + 14, W - 20, 14);
        g2.setStroke(new BasicStroke(1));
        g2.setColor(Color.WHITE); g2.setFont(FONT_TINY);
        g2.drawString((int) Math.ceil(hp) + " / " + maxHp, W / 2 - 20, WALL_Y + 25);
    }

    private void drawZombie(Graphics2D g2, Zombie z) {
        double bob = Math.sin(z.wob) * (z.r * 0.12);
        double x = z.x, y = z.y + bob;

        // 手臂
        g2.setColor(z.col); g2.setStroke(new BasicStroke((float) Math.max(3, z.r * 0.28), BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.draw(new java.awt.geom.Line2D.Double(x - z.r * 0.6, y, x - z.r * 1.1, y + z.r * 0.5 + bob));
        g2.draw(new java.awt.geom.Line2D.Double(x + z.r * 0.6, y, x + z.r * 1.1, y + z.r * 0.5 - bob));
        g2.setStroke(new BasicStroke(1));
        // 身体
        g2.setColor(z.col);
        g2.fillOval((int) (x - z.r), (int) (y - z.r * 1.05), (int) (z.r * 2), (int) (z.r * 2.1));
        if (z.flash > 0) {
            g2.setColor(withAlpha(Color.WHITE, (float) (clamp(z.flash / 90, 0, 1) * 0.8)));
            g2.fillOval((int) (x - z.r), (int) (y - z.r * 1.05), (int) (z.r * 2), (int) (z.r * 2.1));
        }
        // 眼睛
        g2.setColor(new Color(30, 12, 12));
        double er = z.r * 0.16;
        g2.fillOval((int) (x - z.r * 0.35 - er), (int) (y - z.r * 0.15 - er), (int) (er * 2), (int) (er * 2));
        g2.fillOval((int) (x + z.r * 0.35 - er), (int) (y - z.r * 0.15 - er), (int) (er * 2), (int) (er * 2));

        // 血条（非满血才画）
        if (z.hp < z.maxHp) {
            int bw = (int) (z.r * 2);
            double p = clamp(z.hp / z.maxHp, 0, 1);
            g2.setColor(withAlpha(Color.BLACK, 0.5f));
            g2.fillRect((int) (x - z.r), (int) (y - z.r - 8), bw, 4);
            g2.setColor(HP);
            g2.fillRect((int) (x - z.r), (int) (y - z.r - 8), (int) (bw * p), 4);
        }
    }

    private void drawCannon(Graphics2D g2) {
        double x = px, y = CANNON_Y;
        // 炮管（沿 aim 方向的一根圆角条）
        g2.setColor(BARREL);
        double len = PR + 16, thick = 10;
        double ex = x + Math.cos(aim) * len, ey = y + Math.sin(aim) * len;
        g2.setStroke(new BasicStroke((float) thick, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
        g2.drawLine((int) x, (int) y, (int) ex, (int) ey);
        g2.setStroke(new BasicStroke(1));
        // 炮口火光
        if (flash > 0) {
            double f = clamp(flash / 90, 0, 1);
            g2.setColor(withAlpha(MUZZLE, (float) f));
            double rr = 8 + 10 * f;
            g2.fillOval((int) (ex - rr), (int) (ey - rr), (int) (rr * 2), (int) (rr * 2));
        }
        // 基座（半圆炮塔）
        g2.setColor(CANNON_DK);
        g2.fillArc((int) (x - PR - 4), (int) (y + 4 - PR - 4), (int) ((PR + 4) * 2), (int) ((PR + 4) * 2), 0, 180);
        g2.setColor(CANNON);
        g2.fillArc((int) (x - PR), (int) (y + 2 - PR), PR * 2, PR * 2, 0, 180);
        g2.setColor(withAlpha(Color.WHITE, 0.25f));
        double hr = PR * 0.28;
        g2.fillOval((int) (x - PR * 0.35 - hr), (int) (y - PR * 0.35 - hr), (int) (hr * 2), (int) (hr * 2));
    }

    private void drawBullets(Graphics2D g2) {
        g2.setColor(BULLET);
        for (Bullet b : bullets) {
            g2.fillOval((int) (b.x - b.r), (int) (b.y - b.r), (int) (b.r * 2), (int) (b.r * 2));
        }
    }

    private void drawParts(Graphics2D g2) {
        for (Part p : parts) {
            g2.setColor(withAlpha(p.col, (float) clamp(p.life / p.max, 0, 1)));
            g2.fillRect((int) (p.x - p.sz / 2), (int) (p.y - p.sz / 2), (int) p.sz, (int) p.sz);
        }
    }

    private void drawFloaters(Graphics2D g2) {
        for (Floater f : floaters) {
            g2.setColor(withAlpha(f.col, (float) clamp(f.life / f.max, 0, 1)));
            g2.setFont(new Font("Microsoft YaHei", Font.BOLD, (int) f.size));
            g2.drawString(f.text, (int) f.x - 12, (int) f.y);
        }
    }

    private void drawHud(Graphics2D g2) {
        g2.setColor(Color.WHITE); g2.setFont(FONT_HUD);
        g2.drawString("第 " + wave + " 波", 12, 24);
        String s = "得分 " + score;
        g2.drawString(s, W - 12 - g2.getFontMetrics().stringWidth(s), 24);

        double xpP = clamp(xp / (double) xpNext, 0, 1);
        g2.setColor(new Color(20, 24, 32)); g2.fillRect(12, 34, W - 24, 8);
        g2.setColor(XP); g2.fillRect(12, 34, (int) ((W - 24) * xpP), 8);
        g2.setColor(TEXT_DIM); g2.setFont(FONT_TINY);
        g2.drawString("Lv." + level, W / 2 - 14, 52);
    }

    private void drawOverlay(Graphics2D g2) {
        g2.setColor(withAlpha(new Color(6, 8, 12), 0.72f)); g2.fillRect(0, 0, W, H);
        g2.setColor(MUZZLE); g2.setFont(FONT_TITLE);
        String t = "向僵尸开炮";
        g2.drawString(t, W / 2 - g2.getFontMetrics().stringWidth(t) / 2, H / 2 - 50);
        g2.setColor(Color.WHITE); g2.setFont(FONT_HUD);
        String tip = state == READY ? "按空格 / 点击开始" : "按空格再来一局";
        g2.drawString(tip, W / 2 - g2.getFontMetrics().stringWidth(tip) / 2, H / 2 + 10);
        if (state == OVER) {
            g2.setColor(HP_LOW); g2.setFont(FONT_SUB);
            g2.drawString("城墙失守", W / 2 - g2.getFontMetrics().stringWidth("城墙失守") / 2, H / 2 - 100);
            g2.setColor(Color.WHITE); g2.setFont(FONT_HUD);
            String om = overMsg;
            g2.drawString(om, W / 2 - g2.getFontMetrics().stringWidth(om) / 2, H / 2 - 20);
        }
        g2.setColor(TEXT_DIM); g2.setFont(FONT_SMALL);
        String hs = "最高分 " + highScore;
        g2.drawString(hs, W / 2 - g2.getFontMetrics().stringWidth(hs) / 2, H / 2 + 40);
    }

    private void drawLevelUp(Graphics2D g2) {
        g2.setColor(withAlpha(new Color(6, 8, 12), 0.7f)); g2.fillRect(0, 0, W, H);
        g2.setColor(XP); g2.setFont(FONT_SUB);
        String head = "升级！选一个强化";
        int topY = cardIdx.isEmpty() ? 160 : cardY[0] - 48;
        g2.drawString(head, W / 2 - g2.getFontMetrics().stringWidth(head) / 2, topY);

        for (int i = 0; i < cardIdx.size(); i++) {
            int idx = cardIdx.get(i);
            Color rc = RAR_COL[U_RAR[idx]];
            g2.setColor(CARD_BG);
            g2.fill(new RoundRectangle2D.Double(cardX[i], cardY[i], cardW[i], cardH[i], 12, 12));
            // 边框按稀有度上色，SSR 及以上描粗一点
            g2.setColor(withAlpha(rc, 0.95f)); g2.setStroke(new BasicStroke(U_RAR[idx] >= 3 ? 3f : 2f));
            g2.draw(new RoundRectangle2D.Double(cardX[i], cardY[i], cardW[i], cardH[i], 12, 12));
            // 右上角稀有度徽章
            String rn = RAR_NAME[U_RAR[idx]];
            int bw = 36, bh = 22, bx = cardX[i] + cardW[i] - bw - 10, by = cardY[i] + (cardH[i] - bh) / 2;
            g2.setStroke(new BasicStroke(1));
            g2.setColor(withAlpha(rc, 0.18f));
            g2.fill(new RoundRectangle2D.Double(bx, by, bw, bh, 8, 8));
            g2.setColor(withAlpha(rc, 0.9f)); g2.setStroke(new BasicStroke(1.5f));
            g2.draw(new RoundRectangle2D.Double(bx, by, bw, bh, 8, 8));
            g2.setStroke(new BasicStroke(1));
            g2.setColor(rc); g2.setFont(FONT_BADGE);
            g2.drawString(rn, bx + bw / 2 - g2.getFontMetrics().stringWidth(rn) / 2, by + 16);
            // 标题与描述
            g2.setColor(Color.WHITE); g2.setFont(FONT_HUD);
            g2.drawString((i + 1) + ". " + U_TITLE[idx], cardX[i] + 16, cardY[i] + 26);
            g2.setColor(TEXT_DIM); g2.setFont(FONT_SMALL);
            g2.drawString(U_DESC[idx], cardX[i] + 16, cardY[i] + 48);
        }
        g2.setColor(TEXT_DIM); g2.setFont(FONT_TINY);
        String tip = "按 1 / 2 / 3 选择";
        g2.drawString(tip, W / 2 - g2.getFontMetrics().stringWidth(tip) / 2, H / 2 + 150);
    }

    // ============ 八、输入 ============
    boolean keyLeft, keyRight;

    private void handleMove(int mx) {
        if (state == PLAYING) { wantX = clamp(mx, PR + 4, W - PR - 4); }
    }

    private void handlePress(int mx, int my) {
        if (state == LEVELUP) {
            for (int i = 0; i < cardIdx.size(); i++) {
                if (mx >= cardX[i] && mx <= cardX[i] + cardW[i] && my >= cardY[i] && my <= cardY[i] + cardH[i]) {
                    chooseCard(i); return;
                }
            }
            return;
        }
        if (state == PLAYING) wantX = clamp(mx, PR + 4, W - PR - 4);
        else startGame();
    }

    private class TAdapter extends KeyAdapter {
        @Override public void keyPressed(KeyEvent e) {
            int k = e.getKeyCode();
            if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) keyLeft = true;
            if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) keyRight = true;
            if (k == KeyEvent.VK_SPACE) {
                if (state == READY || state == OVER) startGame();
            }
            if (state == LEVELUP) {
                if (k == KeyEvent.VK_1) chooseCard(0);
                if (k == KeyEvent.VK_2) chooseCard(1);
                if (k == KeyEvent.VK_3) chooseCard(2);
            }
        }
        @Override public void keyReleased(KeyEvent e) {
            int k = e.getKeyCode();
            if (k == KeyEvent.VK_LEFT || k == KeyEvent.VK_A) keyLeft = false;
            if (k == KeyEvent.VK_RIGHT || k == KeyEvent.VK_D) keyRight = false;
        }
    }

    // ============ 九、小工具 ============
    static double clamp(double v, double a, double b) { return v < a ? a : v > b ? b : v; }
    double rand(double a, double b) { return a + random.nextDouble() * (b - a); }
    static Color withAlpha(Color c, float a) { return new Color(c.getRed(), c.getGreen(), c.getBlue(), (int) (a * 255)); }
}
